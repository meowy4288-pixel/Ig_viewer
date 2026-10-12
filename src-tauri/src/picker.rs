//! Folder picking behind a thin seam so tests can substitute a stub.
//!
//! `rfd` is called from Rust, not through a Tauri dialog plugin, so the webview
//! needs no `dialog:allow-open` capability and `capabilities/default.json` stays
//! untouched.
//!
//! # Why the GTK backend, not the portal
//!
//! Five attempts were made at this picker on rfd's xdg-portal backend. All failed,
//! and the reason is structural: the portal talks D-Bus through `zbus`, which needs
//! a Tokio reactor *entered*, and a Tauri command has none.
//!
//! 1. sync command, no reactor entered  -> panic, "there is no reactor running"
//! 2. sync command, fresh runtime/call  -> dialog opens, then hangs: `zbus` binds
//!    its connection to the reactor it was spawned on
//! 3. async command, fresh runtime/call -> opens, never resolves
//! 4. async command, existing runtime entered, blocking picker -> deadlocks: the
//!    blocking call holds the thread the reactor needs to drive the portal reply
//!
//! The GTK backend has no D-Bus and no reactor, so the entire class of problem
//! disappears. Nothing is lost that mattered: the portal sandbox only limited which
//! paths a chooser could hand back, and this app already reads the user's export
//! after they point at it.

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
/// Show the platform file chooser for a `.zip`.
///
/// No extension filter: with the GTK backend the filtered dialog did not open at
/// all, while the unfiltered one works. The filter bought nothing anyway -- doIndex
/// refuses any path that does not end in `.zip` before touching it.
pub fn pick_zip_native(title: &str) -> Option<PathBuf> {
    rfd::FileDialog::new().set_title(title).pick_file()
}
