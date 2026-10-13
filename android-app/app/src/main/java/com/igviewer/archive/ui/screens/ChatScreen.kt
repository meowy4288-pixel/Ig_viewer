package com.igviewer.archive.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.igviewer.archive.data.MediaInfo
import com.igviewer.archive.ui.components.ArchAvatar
import com.igviewer.archive.ui.components.EmptyState
import com.igviewer.archive.ui.components.Hairline
import com.igviewer.archive.ui.theme.LocalAnimations
import com.igviewer.archive.ui.theme.LocalCompactRows
import com.igviewer.archive.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class MessageItem(
    val sender: String,
    val text: String,
    val time: String,
    val isMe: Boolean,
    /** Epoch millis, used by the date strip to jump to a day. */
    val tsMs: Long = 0L,
    val isUnsent: Boolean = false,
    /** Call length in ms when this message is a call log, else null. */
    val callDurationMs: Long? = null,
    /** Labels for media that can't be previewed inline (File, or a missing attachment). */
    val attachmentLabels: List<String> = emptyList(),
    /** A shared post/reel link, when this message is one. */
    val shareLink: String? = null,
    val shareText: String? = null,
    /** Absolute, validated file paths of renderable images on this message. */
    val imageFiles: List<String> = emptyList(),
    /** Absolute, validated file paths of voice notes / audio files on this message. */
    val audioFiles: List<String> = emptyList(),
    /** Absolute, validated file paths of videos on this message. */
    val videoFiles: List<String> = emptyList(),
    val reactions: List<String> = emptyList(),
    /** Non-null on the first message of a calendar day; rendered as a centred header. */
    val daySeparator: String? = null,
)

/** One image or video in the per-chat gallery. */
data class GalleryEntry(val path: String, val isVideo: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatName: String,
    messages: List<MessageItem>,
    onBack: () -> Unit,
    /** When non-null, the list jumps to and highlights this kept-message index. */
    scrollToIndex: Int? = null,
    /** The display name treated as "you" in this chat, or null when auto-detected. */
    selfName: String? = null,
    /** Opens the per-chat "who are you" chooser. */
    onChooseSelf: () -> Unit = {},
    /** Start video playback as soon as the full-screen player opens. */
    autoplayVideos: Boolean = false,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var pickerOpen by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf<String?>(null) }
    var videoOverlay by remember { mutableStateOf<String?>(null) }
    var galleryOpen by remember { mutableStateOf(false) }
    val mediaController = rememberMediaController()

    // Every image + video in the chat, in message order, for the gallery.
    val mediaEntries = remember(messages) {
        val out = ArrayList<GalleryEntry>()
        for (m in messages) {
            m.imageFiles.forEach { out.add(GalleryEntry(it, isVideo = false)) }
            m.videoFiles.forEach { out.add(GalleryEntry(it, isVideo = true)) }
        }
        out
    }

    // In-chat search state.
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<Int>>(emptyList()) }
    var matchPos by remember { mutableIntStateOf(0) }
    var highlightIndex by remember { mutableStateOf<Int?>(null) }
    val searchFocus = remember { FocusRequester() }

    fun closeSearch() {
        searchOpen = false
        query = ""
        matches = emptyList()
        highlightIndex = null
    }

    BackHandler(enabled = overlay != null || videoOverlay != null || searchOpen || galleryOpen) {
        when {
            videoOverlay != null -> videoOverlay = null
            overlay != null -> overlay = null
            galleryOpen -> galleryOpen = false
            else -> closeSearch()
        }
    }

    LaunchedEffect(scrollToIndex, messages) {
        val target = scrollToIndex ?: return@LaunchedEffect
        if (messages.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(target.coerceIn(0, messages.lastIndex))
    }

    LaunchedEffect(query, messages) {
        val q = query.trim()
        if (q.isEmpty()) {
            matches = emptyList()
            highlightIndex = null
            return@LaunchedEffect
        }
        val found = messages.indices.filter { messages[it].text.contains(q, ignoreCase = true) }
        matches = found
        matchPos = 0
        if (found.isNotEmpty()) {
            highlightIndex = found[0]
            listState.animateScrollToItem(found[0])
        } else {
            highlightIndex = null
        }
    }

    LaunchedEffect(searchOpen) {
        if (searchOpen) runCatching { searchFocus.requestFocus() }
    }

    fun goToMatch(delta: Int) {
        if (matches.isEmpty()) return
        matchPos = ((matchPos + delta) % matches.size + matches.size) % matches.size
        val idx = matches[matchPos]
        highlightIndex = idx
        scope.launch { listState.animateScrollToItem(idx) }
    }

    // The date strip reflects the day of the topmost visible message, so it reads as a
    // position in the chat rather than today's date. Arrows and the picker move it.
    val firstDayMs = remember(messages) {
        messages.firstOrNull()?.tsMs?.let(::startOfDayMs) ?: startOfDayMs(System.currentTimeMillis())
    }
    val visibleDayMs: Long by remember(messages) {
        derivedStateOf {
            messages.getOrNull(listState.firstVisibleItemIndex)?.tsMs?.let(::startOfDayMs)
                ?: firstDayMs
        }
    }
    fun goToDay(dayMs: Long) {
        if (messages.isEmpty()) return
        val idx = messages.indexOfFirst { it.tsMs >= dayMs }
        scope.launch { listState.scrollToItem(if (idx >= 0) idx else messages.lastIndex) }
    }

    val showJumpToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 5 || listState.firstVisibleItemScrollOffset > 500 }
    }
    val showJumpToBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index < listState.layoutInfo.totalItemsCount - 1
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Tokens.Text)
                }
                ArchAvatar(
                    letter = chatName.ifEmpty { "?" },
                    width = 30.dp,
                    height = 36.dp,
                    fontSize = 15,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                ) {
                    Text(
                        text = chatName.ifEmpty { "—" },
                        color = Tokens.Text,
                        fontFamily = Tokens.Serif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        maxLines = 1,
                    )
                    Text(
                        text = buildString {
                            append("${messages.size} messages")
                            if (!selfName.isNullOrBlank()) append(" · you are $selfName")
                        },
                        color = Tokens.Muted,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
                IconButton(onClick = onChooseSelf) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = "Who am I in this chat",
                        tint = if (selfName.isNullOrBlank()) Tokens.Muted else Tokens.Accent,
                    )
                }
                IconButton(onClick = { galleryOpen = true }, enabled = mediaEntries.isNotEmpty()) {
                    GridIcon(tint = if (mediaEntries.isEmpty()) Tokens.Muted else Tokens.Text)
                }
                IconButton(onClick = { if (searchOpen) closeSearch() else searchOpen = true }) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search this chat",
                        tint = if (searchOpen) Tokens.Accent else Tokens.Text,
                    )
                }
            }
            Hairline()

            if (searchOpen) {
                SearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    count = matches.size,
                    position = if (matches.isEmpty()) 0 else matchPos + 1,
                    focusRequester = searchFocus,
                    onPrev = { goToMatch(-1) },
                    onNext = { goToMatch(+1) },
                    onClose = { closeSearch() },
                )
            }

            DateStrip(
                selectedMs = visibleDayMs,
                onPrev = { goToDay(shiftDayMs(visibleDayMs, -1)) },
                onNext = { goToDay(shiftDayMs(visibleDayMs, +1)) },
                onOpenPicker = { pickerOpen = true },
            )

            if (messages.isEmpty()) {
                EmptyState("No messages to show.")
            } else {
                // One constraint pass for the whole list; the bubble max width is then a
                // plain value, avoiding a BoxWithConstraints (subcomposition) per message.
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    val maxBubble = maxWidth * 0.8f
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 88.dp),
                    ) {
                        itemsIndexed(messages, key = { i, _ -> i }) { i, msg ->
                            val prev = messages.getOrNull(i - 1)
                            val next = messages.getOrNull(i + 1)
                            val groupedTop = prev != null && prev.isMe == msg.isMe && prev.daySeparator == null
                            val groupedBottom = next != null && next.isMe == msg.isMe && next.daySeparator == null
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (i == (highlightIndex ?: scrollToIndex)) {
                                            Modifier.background(Tokens.AccentDim)
                                        } else {
                                            Modifier
                                        },
                                    ),
                            ) {
                                MessageBlock(
                                    msg = msg,
                                    groupedTop = groupedTop,
                                    groupedBottom = groupedBottom,
                                    maxBubble = maxBubble,
                                    controller = mediaController,
                                    onImageClick = { overlay = it },
                                    onVideoClick = { videoOverlay = it },
                                )
                            }
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showJumpToTop) {
                SmallFab(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    light = true,
                ) { Icon(Icons.Default.KeyboardArrowUp, "Jump to start", tint = Tokens.Text) }
            }
            if (showJumpToBottom) {
                SmallFab(
                    onClick = {
                        scope.launch { listState.animateScrollToItem(maxOf(0, messages.size - 1)) }
                    },
                    light = false,
                ) { Icon(Icons.Default.KeyboardArrowDown, "Jump to latest", tint = Tokens.OnAccent) }
            }
        }

        if (galleryOpen) {
            GalleryOverlay(
                entries = mediaEntries,
                onOpenImage = { overlay = it },
                onOpenVideo = { videoOverlay = it },
                onClose = { galleryOpen = false },
            )
        }

        overlay?.let { path ->
            ImageOverlay(path = path, onClose = { overlay = null })
        }

        videoOverlay?.let { path ->
            VideoOverlay(
                path = path,
                onClose = { videoOverlay = null },
                autoplay = autoplayVideos,
            )
        }
    }

    if (pickerOpen) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = visibleDayMs,
        )
        DatePickerDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { goToDay(utcDateToLocalStart(it)) }
                    pickerOpen = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { pickerOpen = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun SmallFab(light: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (light) Tokens.Surface else Tokens.Accent)
            .then(if (light) Modifier.border(1.dp, Tokens.Line, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun DateStrip(
    selectedMs: Long,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenPicker: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrev, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day", tint = Tokens.Text)
            }
            val label = remember(selectedMs) {
                SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(selectedMs)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Tokens.Surface)
                    .border(1.dp, Tokens.Line, RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenPicker),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = label, color = Tokens.Text, fontWeight = FontWeight.Medium)
            }
            IconButton(onClick = onNext, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day", tint = Tokens.Text)
            }
        }
        Hairline()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    count: Int,
    position: Int,
    focusRequester: FocusRequester,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text("Search this chat", color = Tokens.Muted) },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Tokens.Surface,
                unfocusedContainerColor = Tokens.Surface,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Tokens.Text,
                focusedTextColor = Tokens.Text,
                unfocusedTextColor = Tokens.Text,
            ),
        )
        if (query.isNotEmpty()) {
            Text(
                text = if (count == 0) "0/0" else "$position/$count",
                color = Tokens.Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        IconButton(onClick = onPrev, enabled = count > 0) {
            Icon(Icons.Default.KeyboardArrowUp, "Previous match", tint = Tokens.Text)
        }
        IconButton(onClick = onNext, enabled = count > 0) {
            Icon(Icons.Default.KeyboardArrowDown, "Next match", tint = Tokens.Text)
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, "Close search", tint = Tokens.Text)
        }
    }
}

@Composable
private fun MessageBlock(
    msg: MessageItem,
    groupedTop: Boolean,
    groupedBottom: Boolean,
    maxBubble: androidx.compose.ui.unit.Dp,
    controller: MediaPlayerController,
    onImageClick: (String) -> Unit,
    onVideoClick: (String) -> Unit,
) {
    val compact = LocalCompactRows.current
    Column(modifier = Modifier.fillMaxWidth()) {
        if (msg.daySeparator != null) {
            Text(
                text = msg.daySeparator,
                color = Tokens.Gilt,
                fontFamily = Tokens.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (compact) 10.dp else 18.dp, bottom = if (compact) 4.dp else 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        MessageBubble(msg, groupedTop, groupedBottom, maxBubble, controller, onImageClick, onVideoClick)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: MessageItem,
    groupedTop: Boolean,
    groupedBottom: Boolean,
    maxBubble: androidx.compose.ui.unit.Dp,
    controller: MediaPlayerController,
    onImageClick: (String) -> Unit,
    onVideoClick: (String) -> Unit,
) {
    val bg = if (msg.isMe) Tokens.Accent else Tokens.Surface
    val fg = if (msg.isMe) Tokens.OnAccent else Tokens.Text
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val compact = LocalCompactRows.current

    val large = 18.dp
    val small = 4.dp
    val grouped = 6.dp

    val shape = if (msg.isMe) {
        RoundedCornerShape(
            topStart = large,
            topEnd = if (groupedTop) grouped else large,
            bottomEnd = if (groupedBottom) grouped else small,
            bottomStart = if (groupedBottom) grouped else large,
        )
    } else {
        RoundedCornerShape(
            topStart = if (groupedTop) grouped else large,
            topEnd = large,
            bottomEnd = if (groupedBottom) grouped else large,
            bottomStart = if (groupedBottom) grouped else small,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (groupedTop) (if (compact) 1.dp else 2.dp) else (if (compact) 6.dp else 10.dp)),
    ) {
        Box(
            modifier = Modifier
                .align(if (msg.isMe) Alignment.CenterEnd else Alignment.CenterStart)
                .widthIn(max = maxBubble)
                .clip(shape)
                .background(bg)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        if (msg.text.isNotBlank()) {
                            clipboard.setText(AnnotatedString(msg.text))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                .padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 8.dp),
        ) {
            Column {
                if (msg.isUnsent) {
                    Text(
                        text = "Message unsent",
                        color = Tokens.Muted,
                        fontStyle = FontStyle.Italic,
                        fontSize = 15.sp,
                    )
                } else {
                    if (msg.text.isNotBlank()) {
                        LinkText(text = msg.text, color = fg, fontSize = 15.sp)
                    }
                    msg.imageFiles.forEach { path ->
                        MediaImage(path = path, onClick = { onImageClick(path) })
                    }
                    msg.audioFiles.forEach { path ->
                        VoiceNoteBubble(path = path, fg = fg, bg = bg, controller = controller)
                    }
                    msg.videoFiles.forEach { path ->
                        VideoThumb(path = path, fg = fg, onClick = { onVideoClick(path) })
                    }
                    if (msg.callDurationMs != null) {
                        CallChip(durationMs = msg.callDurationMs, fg = fg)
                    }
                    msg.attachmentLabels.forEach { label ->
                        AttachmentChip(label = label, fg = fg)
                    }
                    if (msg.shareLink != null || msg.shareText != null) {
                        ShareCard(link = msg.shareLink, text = msg.shareText, fg = fg)
                    }
                }

                if (msg.reactions.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        msg.reactions.forEach { reaction ->
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Tokens.Bg)
                                    .border(1.dp, Tokens.Line, CircleShape)
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(text = reaction, color = Tokens.Text, fontSize = 12.sp)
                            }
                        }
                    }
                }

                if (!groupedBottom) {
                    Text(
                        text = msg.time,
                        color = fg.copy(alpha = 0.55f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
        }
    }
}

/** Local midnight for the day containing [ms]. */
private fun startOfDayMs(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** [dayMs] shifted by whole days (DST-safe via Calendar), renormalised to midnight. */
private fun shiftDayMs(dayMs: Long, delta: Int): Long = Calendar.getInstance().apply {
    timeInMillis = dayMs
    add(Calendar.DAY_OF_MONTH, delta)
}.timeInMillis.let(::startOfDayMs)

/**
 * Material3's DatePicker reports the selection as UTC midnight of the chosen date.
 * Convert that to local midnight so the strip and the jump agree with what the user
 * picked regardless of timezone.
 */
private fun utcDateToLocalStart(utcMs: Long): Long {
    val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMs }
    return Calendar.getInstance().apply {
        clear()
        set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** http(s) URLs only. Every other scheme stays inert text. */
private val LINK_RE = Regex("""https?://[^\s<>"']+""")

/**
 * Message text with bare http(s) links rendered as tappable. Content is untrusted,
 * so only http/https become links; everything else is plain text.
 */
@Composable
private fun LinkText(text: String, color: Color, fontSize: androidx.compose.ui.unit.TextUnit) {
    val uriHandler = LocalUriHandler.current
    val annotated = remember(text) {
        buildAnnotatedString {
            var last = 0
            for (m in LINK_RE.findAll(text)) {
                if (m.range.first > last) append(text.substring(last, m.range.first))
                val url = m.value
                withLink(
                    LinkAnnotation.Url(
                        url = url,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = Tokens.Accent,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ),
                        linkInteractionListener = LinkInteractionListener { uriHandler.openUri(url) },
                    ),
                ) { append(url) }
                last = m.range.last + 1
            }
            if (last < text.length) append(text.substring(last))
        }
    }
    Text(text = annotated, color = color, fontSize = fontSize)
}

/** A 2x2 grid glyph, drawn because no grid icon ships in the core icon set. */
@Composable
private fun GridIcon(tint: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val gap = this.size.width * 0.16f
        val cell = (this.size.width - gap) / 2f
        val r = CornerRadius(cell * 0.22f, cell * 0.22f)
        val cells = listOf(
            Offset(0f, 0f),
            Offset(cell + gap, 0f),
            Offset(0f, cell + gap),
            Offset(cell + gap, cell + gap),
        )
        for (o in cells) {
            drawRoundRect(color = tint, topLeft = o, size = Size(cell, cell), cornerRadius = r)
        }
    }
}

/** A full-screen grid of every image and video in the chat. */
@Composable
private fun GalleryOverlay(
    entries: List<GalleryEntry>,
    onOpenImage: (String) -> Unit,
    onOpenVideo: (String) -> Unit,
    onClose: () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val animations = LocalAnimations.current
    val fade by animateFloatAsState(if (shown) 1f else 0f, tween(if (animations) 160 else 0), label = "gallery")
    Column(
        modifier = Modifier
            .fillMaxSize()
            .alpha(fade)
            .background(Tokens.Bg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close gallery", tint = Tokens.Text)
            }
            Text(
                text = "Media · ${entries.size}",
                color = Tokens.Text,
                fontFamily = Tokens.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                modifier = Modifier.weight(1f),
            )
        }
        Hairline()
        if (entries.isEmpty()) {
            EmptyState("No media in this chat.")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(entries.size) { i ->
                    val e = entries[i]
                    GridMedia(
                        path = e.path,
                        isVideo = e.isVideo,
                        onClick = { if (e.isVideo) onOpenVideo(e.path) else onOpenImage(e.path) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GridMedia(path: String, isVideo: Boolean, onClick: () -> Unit) {
    val bmp by produceState<ImageBitmap?>(initialValue = null, path, isVideo) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (isVideo) {
                    MediaInfo.poster(path, 320)?.asImageBitmap()
                } else {
                    decodeScaled(path, 320)
                }
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(Tokens.Surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val img = bmp
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isVideo) {
            PlayPauseBadge(
                playing = false,
                fg = Color.White,
                bg = Color.Black.copy(alpha = 0.5f),
                size = 30.dp,
            )
        }
    }
}

/** A small bordered chip naming a non-previewable attachment (Video, GIF, File, ...). */
@Composable
private fun AttachmentChip(label: String, fg: Color) {
    Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Tokens.Bg)
            .border(1.dp, Tokens.Line, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text = "[$label]", color = fg.copy(alpha = 0.85f), fontSize = 13.sp)
    }
}

/** A call log entry: a bordered chip naming the call and its length when known. */
@Composable
private fun CallChip(durationMs: Long, fg: Color) {
    val length = MediaInfo.formatDuration(durationMs)
    val label = if (length.isEmpty()) "Call" else "Call · $length"
    Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Tokens.Bg)
            .border(1.dp, Tokens.Line, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text = label, color = fg.copy(alpha = 0.85f), fontSize = 13.sp)
    }
}

/** A shared post/reel: its caption (link-aware) and the link itself. */
@Composable
private fun ShareCard(link: String?, text: String?, fg: Color) {
    val kind = when {
        link == null -> "Shared post"
        link.contains("/reel") -> "Shared reel"
        link.contains("/stories") -> "Shared story"
        link.contains("/tv/") -> "Shared video"
        else -> "Shared post"
    }
    Column(
        modifier = Modifier
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Tokens.Bg)
            .border(1.dp, Tokens.Line, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(text = kind, color = Tokens.Gilt, fontSize = 11.sp)
        if (!text.isNullOrBlank()) {
            Box(modifier = Modifier.padding(top = 3.dp)) {
                LinkText(text = text, color = fg, fontSize = 14.sp)
            }
        }
        if (!link.isNullOrBlank()) {
            Box(modifier = Modifier.padding(top = 3.dp)) {
                LinkText(text = link, color = Tokens.Accent, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun MediaImage(path: String, onClick: () -> Unit) {
    val isGif = path.endsWith(".gif", ignoreCase = true)
    val canAnimate = isGif && android.os.Build.VERSION.SDK_INT >= 28
    val key = cacheKey(path, THUMB_PX)
    val image by produceState<ImageBitmap?>(initialValue = imageCache.get(key), path, canAnimate) {
        if (canAnimate) return@produceState
        val cached = imageCache.get(key)
        if (cached != null) {
            value = cached
            return@produceState
        }
        val bmp = withContext(Dispatchers.IO) {
            runCatching { decodeScaled(path, THUMB_PX) }.getOrNull()
        }
        if (bmp != null) imageCache.put(key, bmp)
        value = bmp
    }
    Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .size(width = 220.dp, height = 165.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Tokens.Bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (canAnimate) {
            AnimatedGif(path = path, modifier = Modifier.fillMaxSize())
        } else {
            val img = image
            if (img != null) {
                Image(
                    bitmap = img,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(text = "Image", color = Tokens.Muted, fontSize = 13.sp)
            }
        }
    }
}

/** Plays an animated GIF via ImageDecoder (API 28+); falls back to nothing on older ones. */
@Composable
private fun AnimatedGif(path: String, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            android.widget.ImageView(ctx).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                runCatching {
                    val src = android.graphics.ImageDecoder.createSource(java.io.File(path))
                    val drawable = android.graphics.ImageDecoder.decodeDrawable(src)
                    setImageDrawable(drawable)
                    if (drawable is android.graphics.drawable.AnimatedImageDrawable) {
                        drawable.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
                        drawable.start()
                    }
                }
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun ImageOverlay(path: String, onClose: () -> Unit) {
    val image by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodeScaled(path, 2048) }.getOrNull()
        }
    }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val animations = LocalAnimations.current
    val fade by animateFloatAsState(if (shown) 1f else 0f, tween(if (animations) 160 else 0), label = "imageOverlay")
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(fade)
            .background(Color.Black.copy(alpha = 0.96f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            )
        } else {
            Text(text = "Loading…", color = Tokens.Text)
        }
    }
}

/** Decode target for inline thumbnails; small enough to keep scrolling smooth. */
private const val THUMB_PX = 640

private fun cacheKey(path: String, maxPx: Int) = "$path@$maxPx"

/** Byte-bounded cache so re-scrolling a chat does not re-decode the same photos. */
private val imageCache = object : android.util.LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

private fun decodeScaled(path: String, maxPx: Int): ImageBitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    val biggest = maxOf(bounds.outWidth, bounds.outHeight)
    while (biggest / sample > maxPx) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = android.graphics.BitmapFactory.decodeFile(path, opts) ?: return null
    return bmp.asImageBitmap()
}
