package com.igviewer.archive.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igviewer.archive.ui.theme.Tokens

@Composable
fun ImportScreen(
    pickedName: String?,
    indexing: Boolean,
    status: String?,
    onPickZip: () -> Unit,
    onPickDir: () -> Unit,
    onIndex: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Open your archive",
            color = Tokens.Text,
            fontFamily = Tokens.Serif,
            fontWeight = FontWeight.Medium,
            fontSize = 28.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Pick your Instagram export (.zip or folder). Everything stays on this phone and the app makes no network requests.",
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
        ) { Text("Choose .zip…", color = Tokens.Text) }
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
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = Tokens.Accent,
                trackColor = Tokens.Surface,
            )
            Spacer(Modifier.height(8.dp))
            Text(text = "Indexing…", color = Tokens.Muted, fontSize = 13.sp)
        }
        if (status != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = status,
                color = Tokens.Gilt,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
