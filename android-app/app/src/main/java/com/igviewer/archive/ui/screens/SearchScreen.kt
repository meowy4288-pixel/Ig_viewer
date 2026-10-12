package com.igviewer.archive.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igviewer.archive.ui.components.EmptyState
import com.igviewer.archive.ui.components.Hairline
import com.igviewer.archive.ui.theme.Tokens

data class SearchResult(
    val key: String,
    val chatName: String,
    val snippet: String,
    val date: String,
    val time: String,
)

@Composable
fun SearchScreen(
    search: (String) -> List<SearchResult>,
    onBack: () -> Unit,
    onResultClick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val results = remember(query) { search(query) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Tokens.Text)
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search all messages", color = Tokens.Muted) },
                singleLine = true,
                shape = CircleShape,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Tokens.Text,
                    unfocusedTextColor = Tokens.Text,
                    focusedBorderColor = Tokens.Line,
                    unfocusedBorderColor = Tokens.Line,
                    cursorColor = Tokens.Accent,
                    focusedContainerColor = Tokens.Surface,
                    unfocusedContainerColor = Tokens.Surface,
                ),
            )
        }
        Hairline()

        when {
            query.length >= 2 && results.isEmpty() -> EmptyState("No messages match \"$query\".")
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(results, key = { it.key + it.snippet + it.time }) { result ->
                    SearchResultRow(result, query) { onResultClick(result.key) }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(result: SearchResult, query: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = result.chatName,
                color = Tokens.Gilt,
                fontFamily = Tokens.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${result.date} · ${result.time}",
                color = Tokens.Muted,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = highlightMatch(result.snippet, query),
            color = Tokens.Text,
            fontSize = 14.sp,
        )
    }
}

private fun highlightMatch(text: String, query: String) = buildAnnotatedString {
    val lowerText = text.lowercase()
    val lowerQuery = query.lowercase()
    var start = 0
    while (true) {
        val idx = lowerText.indexOf(lowerQuery, start)
        if (idx < 0) {
            append(text.substring(start))
            break
        }
        append(text.substring(start, idx))
        withStyle(SpanStyle(background = Tokens.Accent, color = Tokens.OnAccent)) {
            append(text.substring(idx, idx + query.length))
        }
        start = idx + query.length
    }
}
