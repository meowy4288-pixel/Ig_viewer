//! Folder picker behind a thin trait, so tests can substitute a stub.
//!
//! `rfd` is called from Rust, not through a Tauri dialog plugin, so the webview
//! needs no `dialog:allow-open` capability and `capabilities/default.json` stays
//! untouched. Trade-off: `rfd` cannot be driven headlessly, which is exactly why
//! the picker sits behind this trait.

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

/// Real picker: native dialog via `rfd`.
///
/// Synchronous because Tauri commands may block; `rfd` shows a modal dialog, and
/// the app has no other work to do while the user is choosing a folder. Wrapped in a
/// trait so tests can substitute a stub, since a native dialog cannot be driven
/// headlessly.
pub struct NativeFolderPicker;

impl FolderPicker for NativeFolderPicker {
    fn pick_folder(&self, title: &str) -> Option<PathBuf> {
        rfd::FileDialog::new().set_title(title).pick_folder()
    }
}

/// Test stub.
pub struct StubFolderPicker(pub Option<PathBuf>);

impl FolderPicker for StubFolderPicker {
    fn pick_folder(&self, _title: &str) -> Option<PathBuf> {
        self.0.clone()
    }
}
