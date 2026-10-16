package com.igviewer.archive.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import com.igviewer.archive.ui.components.fastScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.igviewer.archive.ui.components.EmptyState
import com.igviewer.archive.ui.components.Hairline
import com.igviewer.archive.data.Note
import com.igviewer.archive.data.NoteStore
import com.igviewer.archive.ui.theme.LocalMotion
import com.igviewer.archive.ui.theme.Tokens
import com.igviewer.archive.ui.theme.sinkOnPress

data class SearchResult(
    val key: String,
    /** Index into the chat's kept-message list, so the result can be scrolled to. */
    val msgIndex: Int,
    val chatName: String,
    val snippet: String,
    val date: String,
    val time: String,
)

/** A user note, paired with the chat it belongs to, for the Notes tab. */
data class NoteResult(
    val note: Note,
    val chatName: String,
    val date: String,
    val time: String,
)

@Composable
fun SearchScreen(
    search: (String) -> List<SearchResult>,
    onBack: () -> Unit,
    onResultClick: (String, Int) -> Unit,
    /** Every note, already filtered by the query by the caller. */
    notes: List<NoteResult> = emptyList(),
    onNoteClick: (NoteResult) -> Unit = {},
    onNoteDelete: (NoteResult) -> Unit = {},
    /** Bumped by the caller after a note is written, so the list refreshes. */
    notesVersion: Int = 0,
) {
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    val messagesState = rememberLazyListState()
    val notesState = rememberLazyListState()

    // Search runs off the composition thread and debounced. It used to be a synchronous
    // scan of every message in every chat on every keystroke, which visibly janked.
    var results by remember { mutableStateOf(emptyList<SearchResult>()) }
    // Keying the effect on `query` gives debouncing for free: a new keystroke cancels the
    // previous search before it finishes. The scan itself runs off the composition thread.
    // This used to be a synchronous pass over every message in every chat, per keystroke.
    LaunchedEffect(query, search) {
        if (query.trim().length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(180)
        val q = query
        results = withContext(Dispatchers.Default) { search(q) }
    }

    // Notes are filtered on the same query as messages, newest first.
    val visibleNotes = remember(notes, query, notesVersion) {
        NoteStore.search(notes.map { it.note }, query).map { n -> notes.first { it.note.id == n.id } }
    }

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
                placeholder = {
                    Text(
                        if (tab == 0) "Search all messages" else "Search your notes",
                        color = Tokens.Muted,
                    )
                },
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchTab("Messages", tab == 0) { tab = 0 }
            SearchTab("Notes · ${notes.size}", tab == 1) { tab = 1 }
        }
        Hairline()

        if (tab == 0) {
            when {
                query.length >= 2 && results.isEmpty() -> EmptyState(
                    "Nothing matches \"$query\"",
                    "Try fewer words, or search your Notes tab instead.",
                )
                else -> LazyColumn(
                    state = messagesState,
                    modifier = Modifier
                        .fillMaxSize()
                        .fastScroll(messagesState, Tokens.Muted),
                ) {
                    items(results, key = { it.key + it.msgIndex + it.snippet + it.time }) { result ->
                        SearchResultRow(result, query) { onResultClick(result.key, result.msgIndex) }
                    }
                }
            }
        } else {
            when {
                visibleNotes.isEmpty() -> EmptyState(
                    if (query.isBlank()) {
                        "No notes yet.\n\nLong-press any message and choose Note."
                    } else {
                        "No notes match \"$query\"."
                    },
                )
                else -> LazyColumn(
                    state = notesState,
                    modifier = Modifier
                        .fillMaxSize()
                        .fastScroll(notesState, Tokens.Muted),
                ) {
                    items(visibleNotes, key = { it.note.id }) { nr ->
                        NoteResultRow(nr, query, onClick = { onNoteClick(nr) }, onDelete = { onNoteDelete(nr) })
                    }
                }
            }
        }
    }
}

/** Tab header pill shared by the Messages and Notes tabs. */
@Composable
private fun SearchTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Tokens.Accent else Tokens.Surface)
            .border(1.dp, if (selected) Tokens.Accent else Tokens.Line, RoundedCornerShape(20.dp))
            .sinkOnPress(LocalMotion.current.press, onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            color = if (selected) Tokens.OnAccent else Tokens.Text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** One note row: the note text, its chat and when it was written. */
@Composable
private fun NoteResultRow(
    nr: NoteResult,
    query: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = nr.chatName,
                color = Tokens.Gilt,
                fontFamily = Tokens.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(text = nr.date, color = Tokens.Muted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDelete) {
                Text("Delete", color = Tokens.Muted, fontSize = 12.sp)
            }
        }
        Text(
            text = highlightMatch(nr.note.text, query),
            color = Tokens.Text,
            fontSize = 14.sp,
        )
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
    // An empty query makes indexOf return the start index forever, which spins this loop
    // without bound and takes the process down with it. The Notes tab renders rows before
    // anything is typed, so this is the normal case, not an edge one.
    if (query.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
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
