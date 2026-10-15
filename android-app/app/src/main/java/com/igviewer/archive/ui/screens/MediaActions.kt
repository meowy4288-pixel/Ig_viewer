package com.igviewer.archive.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igviewer.archive.data.MediaResolver
import com.igviewer.archive.data.MediaSaver
import com.igviewer.archive.data.Note
import com.igviewer.archive.data.NoteStore
import com.igviewer.archive.data.Prefs
import com.igviewer.archive.ui.components.Hairline
import com.igviewer.archive.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The three actions offered when a message (with or without media) is long-pressed. */
enum class MediaAction(val label: String, val hint: String) {
    Copy("Copy", "Copy so you can paste it somewhere else"),
    Download("Download", "Save into your chosen folder"),
    Note("Note", "Attach a note you can search later"),
    Rename("Rename", "Give this person a display name"),
    Photo("Photo", "Choose a picture for this person"),
}

/**
 * Long-press sheet for a message: Copy, Download, Note — in that order.
 *
 * Copy resolves to the media when the message has one (so the real image/video/audio lands
 * on the clipboard), and falls back to the message text for a text-only message. Download
 * writes every media file on the message. Note opens the editor.
 */
@Composable
fun MessageActionsDialog(
    hasText: Boolean,
    mediaCount: Int,
    hasNote: Boolean = false,
    /** Hidden for a self-sent message: renaming yourself would break the "you" detection. */
    senderName: String = "",
    /** Set when this person already has a photo, so the row can offer to change it. */
    senderHasPhoto: Boolean = false,
    onCopy: () -> Unit,
    onDownload: () -> Unit,
    onNote: () -> Unit,
    onRename: () -> Unit,
    onPhoto: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Bg,
        titleContentColor = Tokens.Text,
        textContentColor = Tokens.Muted,
        shape = RoundedCornerShape(18.dp),
        title = { Text("Message", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                ActionRow(MediaAction.Copy, subtitle = when {
                    mediaCount > 0 -> "Copy the media ($mediaCount file${if (mediaCount == 1) "" else "s"})"
                    hasText -> "Copy the text"
                    else -> "Nothing to copy"
                }, onClick = onCopy)
                // Download is hidden on a text-only message rather than shown as a row
                // that quietly does nothing.
                if (mediaCount > 0) {
                    ActionRow(
                        MediaAction.Download,
                        subtitle = "Save $mediaCount file${if (mediaCount == 1) "" else "s"}",
                        onClick = onDownload,
                    )
                }
                ActionRow(
                    MediaAction.Note,
                    subtitle = if (hasNote) "Edit your note" else "Write or edit a note",
                    onClick = onNote,
                )
                if (senderName.isNotBlank()) {
                    ActionRow(MediaAction.Rename, subtitle = senderName, onClick = onRename)
                    ActionRow(
                        MediaAction.Photo,
                        subtitle = if (senderHasPhoto) "Change their photo" else "Add a photo",
                        onClick = onPhoto,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Tokens.Muted) }
        },
    )
}

/** The same sheet minus Copy/Note, used by the media and voice-note libraries. */
@Composable
fun DownloadOnlyDialog(
    fileName: String,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Bg,
        titleContentColor = Tokens.Text,
        shape = RoundedCornerShape(18.dp),
        title = { Text("Download", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(fileName, color = Tokens.Muted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                ActionRow(MediaAction.Download, subtitle = "Save into your chosen folder", onClick = onDownload)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Tokens.Muted) } },
    )
}

/** Chooser shown when a message carries several files and the user pressed Copy. */
@Composable
fun MediaChoiceDialog(
    files: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Bg,
        titleContentColor = Tokens.Text,
        textContentColor = Tokens.Muted,
        shape = RoundedCornerShape(18.dp),
        title = { Text("Copy which file?", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                files.forEach { path ->
                    val name = path.substringAfterLast('/')
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPick(path) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                    ) {
                        Text(name, color = Tokens.Text, fontSize = 14.sp, maxLines = 1)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Tokens.Muted) } },
    )
}

@Composable
private fun ActionRow(action: MediaAction, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        Text(action.label, color = Tokens.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, color = Tokens.Muted, fontSize = 12.sp)
    }
}

/**
 * Note editor. An empty note deletes the existing one, so "clear it" and "remove it" are
 * the same gesture.
 */
@Composable
fun NoteEditorDialog(
    existing: Note?,
    chatName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(existing?.text.orEmpty()) }
    val canDelete = !existing?.text.isNullOrBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Bg,
        titleContentColor = Tokens.Text,
        textContentColor = Tokens.Muted,
        shape = RoundedCornerShape(18.dp),
        title = {
            Text(if (existing == null) "New note" else "Edit note", fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column {
                Text("On a message in $chatName", color = Tokens.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                    placeholder = { Text("Type a note…", color = Tokens.Muted) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text("Save", color = Tokens.Accent) }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canDelete) {
                    TextButton(onClick = { onSave("") }) { Text("Delete", color = Tokens.Muted) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel", color = Tokens.Muted) }
            }
        },
    )
}

/**
 * Runs a copy/download off the main thread and reports the outcome.
 *
 * [folderChooser] is invoked when no download folder has been picked yet; it should open the
 * system folder picker and the caller retries afterwards.
 */
@Composable
fun rememberMediaTransfer(
    prefs: Prefs,
    onNeedFolder: () -> Unit,
    onDone: (String) -> Unit,
): (path: String, copy: Boolean) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(prefs) {
        { path: String, copy: Boolean ->
            val file = File(path)
            if (!file.exists()) {
                onDone("File is missing")
                return@remember
            }
            if (!copy && !MediaSaver.hasFolder(prefs)) {
                onNeedFolder()
                onDone("Choose a download folder first")
                return@remember
            }
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    if (copy) {
                        val mime = MediaResolver.mimeFor(path) ?: MediaResolver.sniffMime(file) ?: "*/*"
                        MediaSaver.copyToClipboard(context, file, mime).map { }
                    } else {
                        val tree = prefs.downloadTreeUri
                        if (tree.isNullOrBlank()) {
                            error("No folder chosen")
                        } else {
                            MediaSaver.download(context, tree, file).map { }
                        }
                    }
                }
                val message = result.fold(
                    onSuccess = { if (copy) "Copied" else "Saved to ${prefs.downloadTreeName}" },
                    onFailure = { it.message ?: "Could not write the file" },
                )
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                onDone(message)
            }
        }
    }
}

/** Small helper so the sheet rows and the settings rows share one look. */
@Composable
fun NoteBadge(count: Int, onClick: () -> Unit) {
    if (count <= 0) return
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Tokens.Surface)
            .border(1.dp, Tokens.Line, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notes", color = Tokens.Text, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Text("$count", color = Tokens.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Re-runs [block] whenever [key] changes, after composition settles. */
@Composable
fun LoadOnChange(key: Any?, block: suspend () -> Unit) {
    LaunchedEffect(key) { block() }
}
/**
 * Rename editor for a contact.
 *
 * Works off the raw export name, and clearing the field removes the override so the
 * export's own spelling comes back. Stored on device only; the export is never rewritten.
 */
@Composable
fun RenameContactDialog(
    rawName: String,
    currentDisplay: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(currentDisplay) }
    val isDefault = currentDisplay == rawName
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Bg,
        titleContentColor = Tokens.Text,
        textContentColor = Tokens.Muted,
        shape = RoundedCornerShape(18.dp),
        title = { Text("Rename", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text("In the export this is \"$rawName\".", color = Tokens.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Display name", color = Tokens.Muted) },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Saved on this phone only. Your export is never changed.",
                    color = Tokens.Muted,
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text("Save", color = Tokens.Accent) }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isDefault) {
                    TextButton(onClick = { onSave(rawName) }) {
                        Text("Reset", color = Tokens.Muted)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel", color = Tokens.Muted) }
            }
        },
    )
}

/**
 * The per-chat person menu, opened by long-pressing the chat's logo in the header.
 *
 * Bundles the three things that are about a person in this conversation rather than about
 * one message: their display name, their photo, and which participant is "you".
 */
@Composable
fun PersonMenuDialog(
    /** Raw export names of everyone in this chat. */
    participants: List<String>,
    chatKey: String,
    prefs: Prefs,
    currentSelf: String?,
    /** Called with the raw name to rename, or null to stop renaming anyone. */
    onRename: (String) -> Unit,
    /** Picks a photo for a person in this chat; both null = the default photo for you. */
    onPickPhoto: (String?, String?) -> Unit,
    /** Marks this person as "you" in this chat. */
    onMakeSelf: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Bg,
        titleContentColor = Tokens.Text,
        textContentColor = Tokens.Muted,
        shape = RoundedCornerShape(18.dp),
        title = { Text("People in this chat", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                participants.forEach { raw ->
                    val display = prefs.renamed(chatKey, raw) ?: raw
                    val photo = prefs.contactAvatar(chatKey, raw)
                    val isSelf = !currentSelf.isNullOrBlank() && raw == currentSelf
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AvatarImage(path = photo, size = 34.dp, fallbackInitial = raw)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = display,
                                    color = Tokens.Text,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                )
                                Text(
                                    text = when {
                                        isSelf && display != raw -> "You are here · was $raw"
                                        isSelf -> "You are here"
                                        display != raw -> "Was $raw"
                                        else -> ""
                                    },
                                    color = Tokens.Muted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TextButton(onClick = { onRename(raw) }) {
                                Text(
                                    text = if (display != raw) "Rename" else "Name",
                                    color = Tokens.Accent,
                                    fontSize = 13.sp,
                                )
                            }
                            TextButton(onClick = { onPickPhoto(chatKey, raw) }) {
                                Text(
                                    text = if (photo != null) "Change photo" else "Add photo",
                                    color = Tokens.Accent,
                                    fontSize = 13.sp,
                                )
                            }
                            if (!isSelf) {
                                TextButton(onClick = { onMakeSelf(raw) }) {
                                    Text("I'm this one", color = Tokens.Accent, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    Hairline()
                }
                Text(
                    text = "Names and photos belong to this chat and follow the person, " +
                        "so changing who \"you\" are never moves them.",
                    color = Tokens.Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Tokens.Muted) }
        },
    )
}
