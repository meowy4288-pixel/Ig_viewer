package com.igviewer.archive.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igviewer.archive.ui.theme.Tokens

/**
 * "Who are you?" chooser. Used both for the global identity and for a per-chat
 * override. Candidates are display names only (never message content).
 */
@Composable
fun WhoAmIDialog(
    title: String,
    candidates: List<String>,
    current: String?,
    allowClear: Boolean,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Surface,
        title = {
            Text(
                text = title,
                color = Tokens.Gilt,
                fontFamily = Tokens.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
            )
        },
        text = {
            if (candidates.isEmpty()) {
                Text("No participants were found for this chat.", color = Tokens.Muted, fontSize = 15.sp)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    candidates.forEach { name ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    1.dp,
                                    if (name == current) Tokens.Accent else Tokens.Line,
                                    RoundedCornerShape(10.dp),
                                )
                                .background(
                                    if (name == current) Tokens.AccentDim else Tokens.Bg,
                                    RoundedCornerShape(10.dp),
                                )
                                .clickable { onPick(name) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(name, color = Tokens.Text, fontSize = 16.sp)
                            if (name == current) {
                                Icon(Icons.Default.Check, contentDescription = "Selected", tint = Tokens.Accent)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (allowClear) {
                TextButton(onClick = onClear) { Text("Use global", color = Tokens.Text) }
            } else {
                TextButton(onClick = onDismiss) { Text("Close", color = Tokens.Text) }
            }
        },
        dismissButton = {
            if (allowClear) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = Tokens.Muted) }
            }
        },
    )
}
