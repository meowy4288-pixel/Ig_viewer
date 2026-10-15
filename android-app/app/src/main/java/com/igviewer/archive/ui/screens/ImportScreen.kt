package com.igviewer.archive.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igviewer.archive.data.ImportProgress
import com.igviewer.archive.data.formatBytes
import com.igviewer.archive.ui.theme.Tokens

@Composable
fun ImportScreen(
    pickedName: String?,
    indexing: Boolean,
    progress: ImportProgress?,
    status: String?,
    onPickZip: () -> Unit,
    onPickDir: () -> Unit,
    onIndex: () -> Unit,
    /** Chosen download destination, or null when none has been picked yet. */
    downloadFolderName: String? = null,
    /** Opens the system folder picker for the download destination. */
    onChooseDownloadFolder: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Import Instagram data",
            color = Tokens.Text,
            fontFamily = Tokens.Serif,
            fontWeight = FontWeight.Medium,
            fontSize = 28.sp,
            textAlign = TextAlign.Center,
        )
        if (status != null) {
            ImportStatusBanner(status)
        }
        Spacer(Modifier.height(18.dp))
        // The download destination is asked for up front, so the first long-press to
        // download never dead-ends.
        if (downloadFolderName.isNullOrBlank()) {
            OutlinedButton(onClick = onChooseDownloadFolder) {
                Text("Choose download folder", color = Tokens.Text, fontSize = 13.sp)
            }
            Text(
                text = "Where photos, videos and voice notes get saved when you long-press them.",
                color = Tokens.Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, start = 12.dp, end = 12.dp),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Downloads go to $downloadFolderName",
                    color = Tokens.Muted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onChooseDownloadFolder) {
                    Text("Change", color = Tokens.Accent, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Import the Instagram data you downloaded. Long-press in the picker to " +
                "select several .zip files at once, or choose the folder you unzipped " +
                "them into. Everything stays on this phone.",
            color = Tokens.Muted,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = onPickZip,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Tokens.Line),
        ) { Text("Choose .zip files…", color = Tokens.Text) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onPickDir,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, Tokens.Line),
        ) { Text("Choose folder…", color = Tokens.Text) }
        Spacer(Modifier.height(12.dp))
        Text(
            text = pickedName?.let { "Selected: $it" } ?: "Nothing selected",
            color = Tokens.Muted,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onIndex,
            enabled = pickedName != null && !indexing,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Tokens.Accent,
                contentColor = Tokens.OnAccent,
                disabledContainerColor = Tokens.Accent.copy(alpha = 0.4f),
                disabledContentColor = Tokens.OnAccent.copy(alpha = 0.5f),
            ),
        ) { Text("Index") }
        Spacer(Modifier.height(16.dp))
        if (indexing) {
            val fraction = progress?.fraction
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = Tokens.Accent,
                    trackColor = Tokens.Surface,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Tokens.Accent,
                    trackColor = Tokens.Surface,
                )
            }
            Spacer(Modifier.height(8.dp))

            val label = when (progress?.phase) {
                ImportProgress.Phase.COPYING -> "Copying files…"
                ImportProgress.Phase.EXTRACTING -> "Extracting archive…"
                ImportProgress.Phase.INDEXING -> "Indexing chats…"
                null -> "Working…"
            }
            Text(text = label, color = Tokens.Muted, fontSize = 13.sp)

            val detail = progress?.let { p ->
                buildString {
                    when {
                        p.totalBytes > 0 ->
                            append("${formatBytes(p.doneBytes)} / ${formatBytes(p.totalBytes)}")
                        p.totalItems > 0 ->
                            append("${p.doneItems} / ${p.totalItems} chats")
                    }
                    val f = p.fraction
                    if (f != null) {
                        if (isNotEmpty()) append("  ·  ")
                        append("${(f * 100).toInt()}%")
                    }
                }
            }
            if (!detail.isNullOrEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(text = detail, color = Tokens.Muted, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * The outcome of the last import attempt.
 *
 * Deliberately rendered at the TOP of the screen: it used to sit at the bottom of a
 * centred, unscrollable column, where a failed import on a small display was pushed
 * off-screen entirely and looked like the app had done nothing.
 */
@Composable
private fun ImportStatusBanner(status: String) {
    val failed = status.startsWith("No chats") || status.startsWith("Import failed")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (failed) Tokens.Surface else Tokens.AccentDim)
            .border(1.dp, if (failed) Tokens.Gilt else Tokens.Line, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (failed) Icons.Default.Info else Icons.Default.Check,
            contentDescription = null,
            tint = Tokens.Gilt,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = status, color = Tokens.Text, fontSize = 13.sp)
    }
}
