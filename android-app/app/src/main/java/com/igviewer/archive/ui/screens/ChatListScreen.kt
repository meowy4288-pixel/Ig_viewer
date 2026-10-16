package com.igviewer.archive.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igviewer.archive.ui.components.AppBar
import com.igviewer.archive.ui.components.ArchAvatar
import com.igviewer.archive.ui.components.EmptyState
import com.igviewer.archive.ui.components.fastScroll
import com.igviewer.archive.ui.theme.LocalMotion
import com.igviewer.archive.ui.theme.ScreenPadding
import com.igviewer.archive.ui.theme.Tokens
import com.igviewer.archive.ui.theme.riseIn
import com.igviewer.archive.ui.theme.sinkOnPress

data class ChatItem(
    val key: String,
    val name: String,
    val lastMessage: String,
    val date: String,
    /** Photo chosen for someone in this chat, if any. */
    val avatarPath: String? = null,
)

@Composable
fun ChatListScreen(
    chats: List<ChatItem>,
    onChatClick: (String) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val chatListState = rememberLazyListState()
    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        AppBar {
            Text(
                text = "IG Archive",
                fontFamily = Tokens.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                color = Tokens.Gilt,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            IconButton(onClick = onSearchClick) {
                Icon(Icons.Default.Search, contentDescription = "Search messages", tint = Tokens.Text)
            }
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Tokens.Text)
            }
        }
        if (chats.isEmpty()) {
            EmptyState("No chats yet.\nImport an archive to get started.")
        } else {
            LazyColumn(
                state = chatListState,
                modifier = Modifier
                    .fillMaxSize()
                    .fastScroll(chatListState, Tokens.Muted),
            ) {
                itemsIndexed(chats, key = { _, chat -> chat.key }) { i, chat ->
                    ChatRow(chat = chat, index = i, onClick = { onChatClick(chat.key) })
                }
            }
        }
    }
}

@Composable
private fun ChatRow(chat: ChatItem, index: Int, onClick: () -> Unit) {
    val motion = LocalMotion.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .riseIn(index, motion.listRise)
            .sinkOnPress(motion.press, onClick)
            .padding(horizontal = ScreenPadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chat.avatarPath != null) {
            AvatarImage(path = chat.avatarPath, size = 40.dp)
        } else {
            ArchAvatar(letter = chat.name)
        }
        Spacer(Modifier.width(14.dp))
        // A fixed minimum height for the text block keeps every row the same height,
        // so a one-line preview and a two-line one do not make the list jump.
        Column(modifier = Modifier.weight(1f).heightIn(min = 42.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = chat.name,
                    color = Tokens.Text,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = chat.date,
                    color = Tokens.Muted,
                    fontSize = 12.sp,
                )
            }
            Text(
                text = chat.lastMessage,
                color = Tokens.Muted,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
