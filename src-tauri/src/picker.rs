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

/// Show the platform folder chooser. Await this from a Tauri async command so it
/// runs on Tauri's runtime rather than a throwaway one.
pub async fn pick_folder_native(title: &str) -> Option<PathBuf> {
    rfd::AsyncFileDialog::new()
        .set_title(title)
        .pick_folder()
        .await
        .map(|h| h.path().to_path_buf())
}

/// Show the platform file chooser, restricted to `.zip`. Same runtime requirement
/// as `pick_folder_native`.
pub async fn pick_zip_native(title: &str) -> Option<PathBuf> {
    rfd::AsyncFileDialog::new()
        .set_title(title)
        .add_filter("Instagram export", &["zip"])
        .pick_file()
        .await
        .map(|h| h.path().to_path_buf())
}