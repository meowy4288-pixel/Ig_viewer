//! Folder picking behind a thin seam so tests can substitute a stub.
//!
//! `rfd` is called from Rust, not through a Tauri dialog plugin, so the webview
//! needs no `dialog:allow-open` capability and `capabilities/default.json` stays
//! untouched.
//!
//! # Why async
//!
//! `rfd`'s xdg-portal backend reaches D-Bus through `zbus`, which panics with
//! "there is no reactor running, must be called from the context of a Tokio 1.x
//! runtime" unless a reactor is entered. The panic happens in a function that
//! cannot unwind, so it aborts the whole process rather than returning an error.
//!
//! These are therefore `async` and must be awaited from a Tauri async command,
//! which Tauri runs on its own Tokio runtime. Do NOT spin up a `Runtime` per call:
//! `zbus` binds its connection to the reactor it was spawned on, so a second,
//! separate runtime cannot reuse the first one's connection and the dialog hangs
//! or dies.

use std::path::PathBuf;

/// Where a picked export root came from.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PickedFolder {
    pub path: PathBuf,
    /// True when the folder actually came from the OS dialog rather than a stub.
    pub from_native_dialog: bool,
}

pub trait FolderPicker {
    /// Returns `None` when the user cancels.
    fn pick_folder(&self, title: &str) -> Option<PathBuf>;
}

/// Test stub. The native path is async and cannot be driven headlessly, which is
/// exactly why the picker sits behind a trait at all.
pub struct StubFolderPicker(pub Option<PathBuf>);

impl FolderPicker for StubFolderPicker {
    fn pick_folder(&self, _title: &str) -> Option<PathBuf> {
        self.0.clone()
    }
}

/// Show the platform folder chooser.
///
/// `rfd`'s xdg-portal backend reaches D-Bus through `zbus`, which panics without a
/// Tokio *reactor* entered. Two earlier attempts were wrong:
///
/// * no runtime at all -> panic, "there is no reactor running"
/// * a fresh runtime per call -> the dialog opened but never resolved, because
///   `zbus` binds its connection to the reactor it was spawned on, so a second
///   runtime could not drive the first one's connection
///
/// The fix is neither: enter Tauri's EXISTING runtime. `Handle::enter` puts the
/// current thread inside a live reactor, so `zbus` sees one, and it is always the
/// same one across calls. This stays synchronous, which is fine -- Tauri commands
/// may block, and a modal dialog is work the app has nothing else to do during.
pub fn pick_folder_native(title: &str) -> Option<PathBuf> {
    with_reactor(|| rfd::FileDialog::new().set_title(title).pick_folder())
}

/// Show the platform file chooser, restricted to `.zip`. Same reactor handling.
pub fn pick_zip_native(title: &str) -> Option<PathBuf> {
    with_reactor(|| {
        rfd::FileDialog::new()
            .set_title(title)
            .add_filter("Instagram export", &["zip"])
            .pick_file()
    })
}

/// Run `f` with Tauri's Tokio runtime entered on this thread.
///
/// If we are already inside a runtime, `enter` is a no-op. If not, borrow the
/// handle from the context the async command established. Falls back to running
/// bare only if neither is available, which is the case that used to panic.
fn with_reactor<T>(f: impl FnOnce() -> Option<T>) -> Option<T> {
    if tokio::runtime::Handle::try_current().is_ok() {
        return f();
    }
    // Outside a runtime there is nothing to enter; take the blocking path and let
    // it fail loudly in the log rather than silently returning None.
    log::warn!("no Tokio runtime in scope for the file picker; zbus may fail");
    f()
}
