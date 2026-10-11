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
        // The xdg-portal backend reaches D-Bus through zbus, which panics with
        // "there is no reactor running, must be called from the context of a Tokio
        // 1.x runtime" when no reactor is entered. A Tauri command runs on the main
        // thread with no runtime, so drive an explicit current-thread one and let
        // block_on() pump the reactor while the modal dialog is up.
        //
        // Calling the blocking FileDialog::pick_folder() here would deadlock instead:
        // it would hold the thread that the reactor needs to make progress.
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .ok()?;
        let picked = rt.block_on(async {
            rfd::AsyncFileDialog::new()
                .set_title(title)
                .pick_folder()
                .await
        });
        picked.map(|h| h.path().to_path_buf())
    }
}

/// Test stub.
pub struct StubFolderPicker(pub Option<PathBuf>);

impl FolderPicker for StubFolderPicker {
    fn pick_folder(&self, _title: &str) -> Option<PathBuf> {
        self.0.clone()
    }
}
