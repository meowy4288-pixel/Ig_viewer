package com.igviewer.archive.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
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
import com.igviewer.archive.ui.components.ArchAvatar
import com.igviewer.archive.ui.components.EmptyState
import com.igviewer.archive.ui.components.Hairline
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
    /** Labels for media that can't be previewed inline (Video, Voice note, File, ...). */
    val attachmentLabels: List<String> = emptyList(),
    /** A shared post/reel link, when this message is one. */
    val shareLink: String? = null,
    val shareText: String? = null,
    /** Absolute, validated file paths of renderable images on this message. */
    val imageFiles: List<String> = emptyList(),
    val reactions: List<String> = emptyList(),
    /** Non-null on the first message of a calendar day; rendered as a centred header. */
    val daySeparator: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatName: String,
    messages: List<MessageItem>,
    onBack: () -> Unit,
    onSearchClick: () -> Unit,
    /** When non-null, the list jumps to and highlights this kept-message index. */
    scrollToIndex: Int? = null,
    /** The display name treated as "you" in this chat, or null when auto-detected. */
    selfName: String? = null,
    /** Opens the per-chat "who are you" chooser. */
    onChooseSelf: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var pickerOpen by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = overlay != null) { overlay = null }

    LaunchedEffect(scrollToIndex, messages) {
        val target = scrollToIndex ?: return@LaunchedEffect
        if (messages.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(target.coerceIn(0, messages.lastIndex))
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
                IconButton(onClick = onSearchClick) {
                    Icon(Icons.Default.Search, contentDescription = "Search this chat", tint = Tokens.Text)
                }
            }
            Hairline()

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
                                        if (i == scrollToIndex) {
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
                                    onImageClick = { overlay = it },
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

        overlay?.let { path ->
            ImageOverlay(path = path, onClose = { overlay = null })
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

@Composable
private fun MessageBlock(
    msg: MessageItem,
    groupedTop: Boolean,
    groupedBottom: Boolean,
    maxBubble: androidx.compose.ui.unit.Dp,
    onImageClick: (String) -> Unit,
) {
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
                    .padding(top = 18.dp, bottom = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        MessageBubble(msg, groupedTop, groupedBottom, maxBubble, onImageClick)
    }
}

@Composable
private fun MessageBubble(
    msg: MessageItem,
    groupedTop: Boolean,
    groupedBottom: Boolean,
    maxBubble: androidx.compose.ui.unit.Dp,
    onImageClick: (String) -> Unit,
) {
    val bg = if (msg.isMe) Tokens.Accent else Tokens.Surface
    val fg = if (msg.isMe) Tokens.OnAccent else Tokens.Text

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

    Box(modifier = Modifier.fillMaxWidth().padding(top = if (groupedTop) 2.dp else 10.dp)) {
        Box(
            modifier = Modifier
                .align(if (msg.isMe) Alignment.CenterEnd else Alignment.CenterStart)
                .widthIn(max = maxBubble)
                .clip(shape)
                .background(bg)
                .padding(horizontal = 12.dp, vertical = 8.dp),
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
    val key = cacheKey(path, THUMB_PX)
    val image by produceState<ImageBitmap?>(initialValue = imageCache.get(key), path) {
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

@Composable
private fun ImageOverlay(path: String, onClose: () -> Unit) {
    val image by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodeScaled(path, 2048) }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
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
