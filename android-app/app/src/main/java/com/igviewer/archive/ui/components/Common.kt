package com.igviewer.archive.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igviewer.archive.ui.theme.Tokens

/** Top app bar: 56dp tall, 1dp bottom hairline, matching `.bar` in DESIGN.html. */
@Composable
fun AppBar(content: @Composable RowScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        Hairline()
    }
}

@Composable
fun Hairline() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .size(1.dp)
            .background(Tokens.Line),
    )
}

/** The arch/tombstone avatar (`.arch`). */
@Composable
fun ArchAvatar(letter: String, width: Dp = 36.dp, height: Dp = 44.dp, fontSize: Int = 17) {
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(ArchShape)
            .background(Tokens.AccentDim)
            .border(1.dp, Tokens.Gilt, ArchShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter.take(1).uppercase(),
            color = Tokens.Text,
            fontFamily = Tokens.Serif,
            fontWeight = FontWeight.SemiBold,
            fontSize = fontSize.sp,
        )
    }
}

@Composable
fun EmptyState(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            color = Tokens.Muted,
            textAlign = TextAlign.Center,
            fontSize = 15.sp,
        )
    }
}
