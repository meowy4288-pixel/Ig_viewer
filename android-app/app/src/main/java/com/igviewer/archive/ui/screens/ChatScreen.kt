package com.igviewer.archive.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.igviewer.archive.data.MediaInfo
import com.igviewer.archive.data.Note
import com.igviewer.archive.data.NoteStore
import com.igviewer.archive.data.Prefs
import com.igviewer.archive.ui.components.ArchAvatar
import com.igviewer.archive.ui.components.EmptyState
import com.igviewer.archive.ui.components.Hairline
import com.igviewer.archive.ui.theme.LocalAnimations
import com.igviewer.archive.ui.theme.LocalCompactRows
import com.igviewer.archive.ui.theme.LocalMotion
import com.igviewer.archive.ui.theme.ThumbDiskCache
import com.igviewer.archive.ui.theme.Tokens
import com.igviewer.archive.ui.theme.dayTick
import com.igviewer.archive.ui.theme.flare
import com.igviewer.archive.ui.theme.popIn
import com.igviewer.archive.ui.theme.popOut
import com.igviewer.archive.ui.theme.riseIn
import com.igviewer.archive.ui.theme.settleIn
import com.igviewer.archive.ui.theme.sinkOnPress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    /** Position in the kept-message list; the identity a user note is pinned to. */
    val msgIndex: Int = -1,
    /**
     * The sender exactly as the export spells it. [sender] is the (possibly renamed)
     * display name; this stays raw because "is this me" and "which contact is this" must
     * both resolve against the export, never against a user-supplied label.
     */
    val rawSender: String = sender,
    /** Photo chosen for this person in this chat, if any. */
    val avatarPath: String? = null,
)

/** One image or video in the per-chat gallery, tagged with its sender + message. */
data class GalleryEntry(
    val path: String,
    val isVideo: Boolean,
    val msgIndex: Int = -1,
    /** Display name, possibly renamed. */
    val sender: String = "",
    /** Raw export name. Filtering uses this, so a rename never empties the library. */
    val rawSender: String = sender,
)

/** One voice note in the per-chat gallery, tagged with its sender + message. */
data class VoiceEntry(
    val path: String,
    val msgIndex: Int = -1,
    /** Display name, possibly renamed. */
    val sender: String = "",
    /** Raw export name; the library filter matches on this. */
    val rawSender: String = sender,
    val time: String = "",
)

/** A full-screen overlay target: a media path plus the message it came from. */
data class OverlayTarget(val path: String, val msgIndex: Int? = null)

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
    /** Stable per-chat key used to persist the library's participant filter and tab. */
    chatKey: String = "",
    /** Identifies the imported archive so user notes can be dropped when it is removed. */
    archiveKey: String = "",
    /** Opens the system folder picker when a download needs a destination. */
    onNeedDownloadFolder: () -> Unit = {},
    /** Called after a contact is renamed, so lists re-map their names. */
    onRenamed: () -> Unit = {},
    /** Applies a per-chat "who are you" choice immediately. */
    onChooseSelfFor: (String) -> Unit = {},
    /** Bumped by the host when a photo is stored, so avatars repaint. */
    avatarVersion: Int = 0,
    /** Opens the photo picker for a person in this chat (both null = your default photo). */
    onPickAvatar: (String?, String?) -> Unit = { _, _ -> },
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var pickerOpen by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf<OverlayTarget?>(null) }
    var videoOverlay by remember { mutableStateOf<OverlayTarget?>(null) }
    var galleryOpen by remember { mutableStateOf(false) }
    val mediaController = rememberMediaController()
    val motion = LocalMotion.current

    // Long-press state: which message the sheet is for, and what the sheet is offering.
    var actionTarget by remember { mutableStateOf<MessageItem?>(null) }
    var noteTarget by remember { mutableStateOf<MessageItem?>(null) }
    var copyChoices by remember { mutableStateOf<List<String>?>(null) }
    var renameTarget by remember { mutableStateOf<MessageItem?>(null) }
    /** Long-press on the chat logo opens the per-person menu. */
    var personMenuOpen by remember { mutableStateOf(false) }
    var personPhotoTarget by remember { mutableStateOf<String?>(null) }
    var personRenameTarget by remember { mutableStateOf<String?>(null) }
    var libraryDownload by remember { mutableStateOf<String?>(null) }
    var notesVersion by remember { mutableIntStateOf(0) }
    // Bubbles settle in once when the chat opens, then never again (so scrolling a huge
    // chat does not replay the cascade).
    // Keyed on the chat, NOT on `messages`: the list identity changes as more messages
    // arrive, and re-keying this made the cascade replay on every update.
    var openCascade by remember(chatKey) { mutableStateOf(true) }
    LaunchedEffect(chatKey) {
        openCascade = true
        delay(650)
        openCascade = false
    }

    // Every image + video in the chat, in message order, for the gallery.
    val mediaEntries = remember(messages) {
        val out = ArrayList<GalleryEntry>()
        messages.forEachIndexed { i, m ->
            m.imageFiles.forEach {
                out.add(GalleryEntry(it, isVideo = false, msgIndex = i, sender = m.sender, rawSender = m.rawSender))
            }
            m.videoFiles.forEach {
                out.add(GalleryEntry(it, isVideo = true, msgIndex = i, sender = m.sender, rawSender = m.rawSender))
            }
        }
        out
    }
    // Every voice note in the chat, for the voice-note gallery.
    val voiceEntries = remember(messages) {
        val out = ArrayList<VoiceEntry>()
        messages.forEachIndexed { i, m ->
            m.audioFiles.forEach {
                out.add(VoiceEntry(it, msgIndex = i, sender = m.sender, rawSender = m.rawSender, time = m.time))
            }
        }
        out
    }
    // People who appear in this chat, for the gallery's sender chooser.
    // Raw export names, never the renamed display names: renames, photos and perspective
    // are all keyed by the raw name, so building this from `sender` silently missed them.
    val participants = remember(messages) {
        messages.map { it.rawSender }.filter { it.isNotBlank() }.distinct()
    }

    // Library state lives here (not inside the overlay) so it survives "View in chat",
    // and is persisted per chat so it survives app restarts.
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val prefs = remember(context) { Prefs(context) }
    var galleryTab by remember(chatKey) { mutableIntStateOf(prefs.galleryTab(chatKey)) }
    var galleryFilter by remember(chatKey) { mutableStateOf(prefs.galleryFilter(chatKey)) }
    // A persisted sender can go stale (re-import with different names); fall back to All.
    val activeFilter = galleryFilter?.takeIf { it in participants }

    // Avatars are per chat AND per person, keyed by the export name, so flipping the
    // perspective moves bubbles across the screen but never swaps anyone's photo.
    val avatarVersionTick = avatarVersion
    val avatarKey = avatarVersionTick
    // Photos are per person in per chat; nothing is derived from the perspective.
    fun avatarFor(raw: String): String? = prefs.contactAvatar(chatKey, raw)

    // Which messages carry a user note, so the bubble can show a marker. Recomputed only
    // when the chat or the note set changes, not per bubble.
    var notedMessages by remember(archiveKey, chatKey) { mutableStateOf(emptyMap<Int, Note>()) }
    LaunchedEffect(archiveKey, chatKey, notesVersion) {
        notedMessages = NoteStore.all(prefs)
            .filter { it.archiveKey == archiveKey && it.threadKey == chatKey }
            .associate { it.msgIndex to it }
    }

    // Copy / download plumbing, shared by the message sheet and the libraries.
    val transfer = rememberMediaTransfer(
        prefs = prefs,
        onNeedFolder = onNeedDownloadFolder,
        onDone = { notesVersion++ },
    )
    /** Every file on this message, in render order. */
    fun mediaOf(m: MessageItem): List<String> = m.imageFiles + m.audioFiles + m.videoFiles
    /** Opens the note editor for a message, pre-filled with whatever is already there. */
    fun editNote(m: MessageItem) {
        // Force a recomposition so a re-opened dialog picks up an edited note.
        notesVersion++
        noteTarget = m
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

    LaunchedEffect(query, chatKey) {
        val q = query.trim()
        if (q.isEmpty()) {
            matches = emptyList()
            highlightIndex = null
            return@LaunchedEffect
        }
        // Debounced and off the main thread: this used to run a full scan of every message
        // on the composition thread for every keystroke, which stutters on a long chat.
        delay(160)
        val snapshot = messages
        val found = withContext(Dispatchers.Default) {
            snapshot.indices.filter { snapshot[it].text.contains(q, ignoreCase = true) }
        }
        if (query.trim() != q) return@LaunchedEffect
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

    /** Close any overlay and jump the chat list to [idx], highlighting that message. */
    fun goToMessage(idx: Int) {
        if (messages.isEmpty() || idx < 0) return
        overlay = null
        videoOverlay = null
        galleryOpen = false
        val target = idx.coerceIn(0, messages.lastIndex)
        highlightIndex = target
        scope.launch { listState.animateScrollToItem(target) }
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
                // Back leaves select mode first; a second back leaves the chat.
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Tokens.Text)
                }
                // The whole header block -- logo AND name -- opens the person menu on
                // long-press. Tapping the bare icon was too small a target to rely on.
                @OptIn(ExperimentalFoundationApi::class)
                Row(
                    modifier = Modifier
                        // Weighted, or its inner weight(1f) column claims the whole row and
                        // pushes the person/grid/search buttons off the right edge.
                        .weight(1f)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { personMenuOpen = true },
                        )
                        .padding(start = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Prefer whoever is NOT you, so a DM's header shows them.
                    val headerAvatar = remember(chatKey, avatarVersion, selfName) {
                        participants.filter { it != selfName }
                            .firstNotNullOfOrNull { prefs.contactAvatar(chatKey, it) }
                            ?: participants.firstNotNullOfOrNull { prefs.contactAvatar(chatKey, it) }
                    }
                    if (headerAvatar != null) {
                        AvatarImage(path = headerAvatar, size = 32.dp)
                    } else {
                        ArchAvatar(
                            letter = chatName.ifEmpty { "?" },
                            width = 30.dp,
                            height = 36.dp,
                            fontSize = 15,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                    ) {
                        Text(
                            text = chatName.ifEmpty { "\u2014" },
                            color = Tokens.Text,
                            fontFamily = Tokens.Serif,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp,
                            maxLines = 1,
                        )
                        Text(
                            text = buildString {
                                append("${messages.size} messages")
                                if (!selfName.isNullOrBlank()) append(" \u00b7 you are $selfName")
                            },
                            color = Tokens.Muted,
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                }
                IconButton(onClick = onChooseSelf) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = "Who am I in this chat",
                        tint = if (selfName.isNullOrBlank()) Tokens.Muted else Tokens.Accent,
                    )
                }
                IconButton(
                    onClick = { galleryOpen = true },
                    enabled = mediaEntries.isNotEmpty() || voiceEntries.isNotEmpty(),
                ) {
                    GridIcon(tint = if (mediaEntries.isEmpty() && voiceEntries.isEmpty()) Tokens.Muted else Tokens.Text)
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
                        itemsIndexed(
                            messages,
                            // Stable identity, not the position. Keying on the index meant
                            // that when more messages arrived, every item lost its
                            // remembered state; msgIndex is absolute and never moves.
                            key = { _, msg -> msg.msgIndex },
                            // Messages differ wildly in shape (text, image, video, voice
                            // note, share card). Telling Compose the type lets it reuse
                            // compositions between like items instead of trying to reuse a
                            // voice-note composition for a text bubble.
                            contentType = { _, msg -> messageContentType(msg) },
                        ) { i, msg ->
                            val prev = messages.getOrNull(i - 1)
                            val next = messages.getOrNull(i + 1)
                            val groupedTop = prev != null && prev.isMe == msg.isMe && prev.daySeparator == null
                            val groupedBottom = next != null && next.isMe == msg.isMe && next.daySeparator == null
                            val isTarget = i == (highlightIndex ?: scrollToIndex)
                            val glow = flare(isTarget, motion.flare)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (isTarget) {
                                            Modifier.background(Tokens.AccentDim.copy(alpha = glow))
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .settleIn(if (openCascade && i <= 8) i else null, motion.cascade),
                            ) {
                                MessageBlock(
                                    msg = msg,
                                    groupedTop = groupedTop,
                                    groupedBottom = groupedBottom,
                                    maxBubble = maxBubble,
                                    controller = mediaController,
                                    onImageClick = { overlay = OverlayTarget(it) },
                                    onVideoClick = { videoOverlay = OverlayTarget(it) },
                                    onLongPress = { actionTarget = msg },
                                    note = notedMessages[msg.msgIndex],
                                    avatarPath = msg.avatarPath,
                                    avatarKey = avatarKey,
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
            AnimatedVisibility(
                visible = showJumpToTop,
                enter = popIn(motion.jump),
                exit = popOut(motion.jump),
            ) {
                SmallFab(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    light = true,
                ) { Icon(Icons.Default.KeyboardArrowUp, "Jump to start", tint = Tokens.Text) }
            }
            AnimatedVisibility(
                visible = showJumpToBottom,
                enter = popIn(motion.jump),
                exit = popOut(motion.jump),
            ) {
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
                media = mediaEntries,
                voices = voiceEntries,
                participants = participants,
                controller = mediaController,
                tab = galleryTab,
                filter = activeFilter,
                onTabChange = { galleryTab = it; prefs.setGalleryTab(chatKey, it) },
                onFilterChange = { galleryFilter = it; prefs.setGalleryFilter(chatKey, it) },
                onOpenImage = { e -> overlay = OverlayTarget(e.path, e.msgIndex) },
                onOpenVideo = { e -> videoOverlay = OverlayTarget(e.path, e.msgIndex) },
                onGoToChat = { idx -> goToMessage(idx) },
                onClose = { galleryOpen = false },
                onDownloadMany = { paths -> paths.forEach { transfer(it, false) } },
                displayNameFor = { raw -> prefs.renamed(chatKey, raw) ?: raw },
            )
        }

        overlay?.let { target ->
            ImageOverlay(
                path = target.path,
                onClose = { overlay = null },
                onViewInChat = target.msgIndex?.let { idx -> { goToMessage(idx) } },
            )
        }

        videoOverlay?.let { target ->
            VideoOverlay(
                path = target.path,
                onClose = { videoOverlay = null },
                autoplay = autoplayVideos,
                onViewInChat = target.msgIndex?.let { idx -> { goToMessage(idx) } },
            )
        }

        // Long-press on a message: Copy, Download, Note.
        actionTarget?.let { target ->
            val files = mediaOf(target)
            MessageActionsDialog(
                hasText = target.text.isNotBlank(),
                mediaCount = files.size,
                hasNote = notedMessages[target.msgIndex] != null,
                onCopy = {
                    actionTarget = null
                    when {
                        files.isEmpty() -> {
                            if (target.text.isNotBlank()) {
                                clipboard.setText(AnnotatedString(target.text))
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            }
                        }
                        files.size == 1 -> transfer(files[0], true)
                        else -> copyChoices = files
                    }
                },
                onDownload = {
                    actionTarget = null
                    files.forEach { transfer(it, false) }
                },
                onNote = {
                    actionTarget = null
                    editNote(target)
                },
                onRename = {
                    actionTarget = null
                    renameTarget = target
                },
                onPhoto = {
                    actionTarget = null
                    onPickAvatar(chatKey, target.rawSender)
                },
                senderHasPhoto = avatarFor(target.rawSender) != null,
                senderName = if (target.isMe) "" else target.rawSender,
                onDismiss = { actionTarget = null },
            )
        }

        // Long-press in a library: Download only.
        libraryDownload?.let { path ->
            DownloadOnlyDialog(
                fileName = path.substringAfterLast('/'),
                onDownload = { libraryDownload = null; transfer(path, false) },
                onDismiss = { libraryDownload = null },
            )
        }

        copyChoices?.let { files ->
            MediaChoiceDialog(
                files = files,
                onPick = { path -> copyChoices = null; transfer(path, true) },
                onDismiss = { copyChoices = null },
            )
        }

        if (personMenuOpen) {
            PersonMenuDialog(
                participants = participants,
                chatKey = chatKey,
                prefs = prefs,
                currentSelf = selfName,
                onRename = { raw ->
                    personMenuOpen = false
                    personRenameTarget = raw
                },
                onPickPhoto = { chat, raw ->
                    personMenuOpen = false
                    onPickAvatar(chat, raw)
                },
                onMakeSelf = { raw ->
                    personMenuOpen = false
                    onChooseSelfFor(raw)
                },
                onDismiss = { personMenuOpen = false },
            )
        }

        personRenameTarget?.let { raw ->
            RenameContactDialog(
                rawName = raw,
                currentDisplay = prefs.renamed(chatKey, raw) ?: raw,
                onSave = { display ->
                    prefs.setRenamed(chatKey, raw, display)
                    personRenameTarget = null
                    onRenamed()
                },
                onDismiss = { personRenameTarget = null },
            )
        }

        renameTarget?.let { target ->
            RenameContactDialog(
                rawName = target.rawSender,
                currentDisplay = prefs.renamed(chatKey, target.rawSender) ?: target.rawSender,
                onSave = { display ->
                    prefs.setRenamed(chatKey, target.rawSender, display)
                    renameTarget = null
                    onRenamed()
                },
                onDismiss = { renameTarget = null },
            )
        }

        noteTarget?.let { target ->
            val existing = remember(target.msgIndex, notesVersion, archiveKey, chatKey) {
                NoteStore.forMessage(prefs, archiveKey, chatKey, target.msgIndex)
            }
            NoteEditorDialog(
                existing = existing,
                chatName = chatName.ifBlank { "this chat" },
                onSave = { text ->
                    NoteStore.save(
                        prefs,
                        Note(
                            archiveKey = archiveKey,
                            threadKey = chatKey,
                            msgIndex = target.msgIndex,
                            text = text,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    noteTarget = null
                    notesVersion++
                },
                onDismiss = { noteTarget = null },
            )
        }
    }

    if (pickerOpen) {
        // Only the years this chat actually spans, derived from its own messages, so the
        // picker can no longer offer dates that never happened.
        val years = remember(messages) { spanYearsOf(messages) }
        ChatDatePickerDialog(
            selectedDayMs = visibleDayMs,
            years = years,
            onPick = { ms ->
                goToDay(ms)
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/**
 * The inclusive calendar years covered by these messages, using local time.
 *
 * Returns a single year when the chat is empty, so the picker is never blank.
 */
private fun spanYearsOf(messages: List<MessageItem>): IntRange {
    if (messages.isEmpty()) {
        val y = java.time.LocalDate.now().year
        return y..y
    }
    var min = Int.MAX_VALUE
    var max = Int.MIN_VALUE
    for (m in messages) {
        val y = java.time.Instant.ofEpochMilli(m.tsMs)
            .atZone(java.time.ZoneId.systemDefault()).year
        if (y < min) min = y
        if (y > max) max = y
    }
    return min..max
}

/**
 * A compact date picker limited to the chat's real span: pick a year, then a month, then a
 * day. Material's own picker always offers a wide year range, which made it easy to jump to
 * a date with nothing in it.
 */
@Composable
private fun ChatDatePickerDialog(
    selectedDayMs: Long,
    years: IntRange,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = remember { java.time.ZoneId.systemDefault() }
    val initial = remember(selectedDayMs) {
        java.time.Instant.ofEpochMilli(selectedDayMs).atZone(zone).toLocalDate()
    }
    var year by remember { mutableIntStateOf(initial.year) }
    var month by remember { mutableIntStateOf(initial.monthValue) }   // 1..12
    var day by remember { mutableIntStateOf(initial.dayOfMonth) }

    // Clamp into range: the day grid shrinks for short months, so never allow a day the
    // chosen month does not have.
    val maxDay = remember(year, month) {
        java.time.YearMonth.of(year, month).lengthOfMonth()
    }
    val safeDay = day.coerceIn(1, maxDay)

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Bg,
        titleContentColor = Tokens.Text,
        shape = RoundedCornerShape(18.dp),
        title = {
            Text(
                text = java.time.YearMonth.of(year, month).month.getDisplayName(
                    java.time.format.TextStyle.SHORT, java.util.Locale.getDefault(),
                ) + " " + year,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column {
                Text("Year", color = Tokens.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Column {
                    years.toList().chunked(4).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            row.forEach { y ->
                                DateCell(
                                    label = "$y",
                                    selected = y == year,
                                    modifier = Modifier.weight(1f),
                                    onClick = { year = y },
                                )
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Month", color = Tokens.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                java.time.Month.entries.chunked(4).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        row.forEach { mo ->
                            DateCell(
                                label = mo.getDisplayName(
                                    java.time.format.TextStyle.SHORT, java.util.Locale.getDefault(),
                                ),
                                selected = mo.getValue() == month,
                                modifier = Modifier.weight(1f),
                                onClick = { month = mo.getValue(); day = 1 },
                            )
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Day", color = Tokens.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                val leading = java.time.YearMonth.of(year, month).atDay(1).dayOfWeek.value % 7
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf("S", "M", "T", "W", "T", "F", "S").forEach { d ->
                        Text(
                            text = d,
                            color = Tokens.Muted,
                            fontSize = 11.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                val cells = leading + maxDay
                val rows = (cells + 6) / 7
                for (r in 0 until rows) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        for (c in 0 until 7) {
                            val n = r * 7 + c - leading + 1
                            if (n in 1..maxDay) {
                                DateCell(
                                    label = "$n",
                                    selected = n == safeDay,
                                    modifier = Modifier.weight(1f),
                                    onClick = { day = n },
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val date = java.time.LocalDate.of(year, month, safeDay)
                onPick(date.atStartOfDay(zone).toInstant().toEpochMilli())
            }) { Text("Go", color = Tokens.Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Tokens.Muted) } },
    )
}

@Composable
private fun DateCell(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Tokens.Accent else Tokens.Surface)
            .sinkOnPress(LocalMotion.current.press, onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Tokens.OnAccent else Tokens.Text,
            fontSize = 13.sp,
            maxLines = 1,
        )
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
            .sinkOnPress(LocalMotion.current.press, onClick),
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
    val motion = LocalMotion.current
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Tokens.Surface)
                    .border(1.dp, Tokens.Line, RoundedCornerShape(10.dp))
                    .sinkOnPress(motion.press, onOpenPicker),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = selectedMs,
                    transitionSpec = dayTick<Long>(motion.dateTick),
                    label = "dayLabel",
                ) { ms ->
                    Text(
                        text = remember(ms) {
                            SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(ms)
                        },
                        color = Tokens.Text,
                        fontWeight = FontWeight.Medium,
                    )
                }
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
    onLongPress: () -> Unit,
    note: Note? = null,
    /** Photo shown beside this message; null falls back to an initial disc. */
    avatarPath: String? = null,
    @Suppress("UNUSED_PARAMETER") avatarKey: Int = 0,
) {
    val compact = LocalCompactRows.current
    Column(modifier = Modifier.fillMaxWidth()) {
        if (msg.daySeparator != null) {
            // Rules either side of the date, so it reads as a divider rather than as
            // content -- which is what it was confused for next to the note strip.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = if (compact) 10.dp else 18.dp,
                        bottom = if (compact) 4.dp else 8.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(Tokens.Line.copy(alpha = 0.7f)),
                )
                Text(
                    text = msg.daySeparator,
                    color = Tokens.Muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(Tokens.Line.copy(alpha = 0.7f)),
                )
            }
        }
        MessageBubble(
            msg, groupedTop, groupedBottom, maxBubble, controller,
            onImageClick, onVideoClick, onLongPress, note, avatarPath, avatarKey,
        )
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
    onLongPress: () -> Unit,
    /** The user's note on this message, or null when there is none. */
    note: Note? = null,
    /** Photo shown beside this message; null falls back to an initial disc. */
    avatarPath: String? = null,
    @Suppress("UNUSED_PARAMETER") avatarKey: Int = 0,
    /** Multi-select: ticked messages get a filled circle. */
    selected: Boolean = false,
    selectMode: Boolean = false,
    onToggleSelected: () -> Unit = {},
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

        // The photo belongs to the person on the LEFT -- the other person relative to the
        // perspective you picked. So viewing as yourself, your own messages show nothing;
        // change who "you" are and your messages move left and your photo comes with them.
        // It hugs the start of the bubble rather than the screen edge, and appears once per
        // run of messages rather than on every one.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (msg.isMe) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
        if (!msg.isMe && !groupedTop) {
            AvatarImage(
                path = avatarPath,
                size = 28.dp,
                fallbackInitial = msg.rawSender,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 2.dp),
            )
        }
        Box(
            modifier = Modifier
                .widthIn(max = maxBubble)
                .then(
                    // A faint rule along the grouped edge, so consecutive messages from one
                    // person read as a single turn.
                    if (!groupedTop) {
                        Modifier
                    } else {
                        Modifier.drawBehind {
                            val x = if (msg.isMe) 0f else size.width
                            drawLine(
                                color = Tokens.Line.copy(alpha = 0.6f),
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 2f * this.density,
                            )
                        }
                    },
                )
                .widthIn(max = maxBubble)
                .clip(shape)
                .background(bg)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress,
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
                        MediaImage(
                            path = path,
                            onClick = { onImageClick(path) },
                            onLongPress = onLongPress,
                        )
                    }
                    msg.audioFiles.forEach { path ->
                        VoiceNoteBubble(
                            path = path,
                            fg = fg,
                            bg = bg,
                            controller = controller,
                            onLongPress = onLongPress,
                        )
                    }
                    msg.videoFiles.forEach { path ->
                        VideoThumb(
                            path = path,
                            fg = fg,
                            onClick = { onVideoClick(path) },
                            onLongPress = onLongPress,
                        )
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

                if (note != null) {
                    NoteStrip(text = note.text, fg = fg, onClick = onLongPress)
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
        }

/**
 * Coarse content type for a message, used only by the lazy list to decide when a composed
 * item can be reused. Anything unrecognised collapses to "text", the common case.
 */
private fun messageContentType(msg: MessageItem): String = when {
    msg.videoFiles.isNotEmpty() -> "video"
    msg.audioFiles.isNotEmpty() -> "voice"
    msg.imageFiles.isNotEmpty() -> "image"
    msg.shareLink != null || msg.shareText != null -> "share"
    msg.attachmentLabels.isNotEmpty() -> "attachment"
    msg.reactions.isNotEmpty() -> "reaction"
    else -> "text"
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

/**
 * The per-chat library: photos/videos and voice notes, filterable by sender so you
 * can view just one participant's media. Each item can jump back to its message.
 */
@Composable
private fun GalleryOverlay(
    media: List<GalleryEntry>,
    voices: List<VoiceEntry>,
    participants: List<String>,
    controller: MediaPlayerController,
    tab: Int,
    filter: String?,
    onTabChange: (Int) -> Unit,
    onFilterChange: (String?) -> Unit,
    onOpenImage: (GalleryEntry) -> Unit,
    onOpenVideo: (GalleryEntry) -> Unit,
    onGoToChat: (Int) -> Unit,
    onClose: () -> Unit,
    /** Copies the given files into the download folder. */
    onDownloadMany: (List<String>) -> Unit,
    /** Maps a raw export name to the display name this chat uses. */
    displayNameFor: (String) -> String = { it },
) {
    var shown by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { shown = true }
    val animations = LocalAnimations.current
    val fade by animateFloatAsState(if (shown) 1f else 0f, tween(if (animations) 160 else 0), label = "gallery")

    val shownMedia = remember(media, filter) { media.filter { filter == null || it.rawSender == filter } }
    val shownVoices = remember(voices, filter) { voices.filter { filter == null || it.rawSender == filter } }
    // Multi-select: paths currently ticked. Empty set means we are browsing, not selecting.
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var selectMode by remember { mutableStateOf(false) }
    // One scroll position per library tab, so returning to a tab lands where you left it.
    val galleryScope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val voicesState = rememberLazyListState()
    val scrollState = if (tab == 0) gridState else voicesState
    val scrolledDown by remember {
        derivedStateOf {
            if (tab == 0) {
                gridState.firstVisibleItemIndex > 3 || gridState.firstVisibleItemScrollOffset > 200
            } else {
                voicesState.firstVisibleItemIndex > 5 || voicesState.firstVisibleItemScrollOffset > 400
            }
        }
    }
    val shownPaths = remember(shownMedia, shownVoices, tab) {
        if (tab == 0) shownMedia.map { it.path } else shownVoices.map { it.path }
    }
    fun toggle(path: String) {
        selected = if (path in selected) selected - path else selected + path
        selectMode = selected.isNotEmpty()
    }

    /** Long-press: enter select mode with this file already ticked. */
    fun startSelect(path: String) {
        selected = selected + path
        selectMode = true
    }
    fun endSelect() {
        selected = emptySet()
        selectMode = false
    }

    // Selection belongs to ONE view. It used to survive a participant filter or a tab
    // switch, so ticking three photos and then switching to voice notes meant Download sent
    // all of them -- including files that were no longer on screen.
    LaunchedEffect(filter, tab) { endSelect() }

    // Belt and braces: never hand Download anything the current view is not showing.
    fun selectedVisible(): List<String> {
        val visible = shownPaths.toSet()
        return selected.filter { it in visible }
    }

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
            IconButton(onClick = if (selectMode) ({ endSelect() }) else onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    if (selectMode) "Leave select mode" else "Close gallery",
                    tint = Tokens.Text,
                )
            }
            Text(
                text = if (selectMode) "${selected.size} selected" else "Library",
                color = Tokens.Text,
                fontFamily = Tokens.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                modifier = Modifier.weight(1f),
            )
            if (selectMode) {
                TextButton(
                    onClick = {
                        // Toggle: "All" clears the selection when everything is already in.
                        val allIn = selected.size == shownPaths.size && shownPaths.isNotEmpty()
                        selected = if (allIn) emptySet() else shownPaths.toSet()
                        selectMode = selected.isNotEmpty()
                    },
                ) {
                    Text(
                        text = if (selected.size == shownPaths.size && shownPaths.isNotEmpty()) {
                            "None"
                        } else {
                            "All"
                        },
                        color = Tokens.Accent,
                        fontSize = 13.sp,
                    )
                }
                TextButton(
                    onClick = { onDownloadMany(selectedVisible()) },
                    enabled = selected.isNotEmpty(),
                ) {
                    Text("Download", color = Tokens.Accent, fontSize = 13.sp)
                }
            } else {
                TextButton(
                    onClick = {
                        if (shownPaths.isNotEmpty()) {
                            selected = shownPaths.toSet()
                            selectMode = true
                        }
                    },
                    enabled = shownPaths.isNotEmpty(),
                ) {
                    Text("Select all", color = Tokens.Accent, fontSize = 13.sp)
                }
                TextButton(
                    onClick = { onDownloadMany(selectedVisible()) },
                    enabled = selected.isNotEmpty(),
                ) {
                    Text("Download", color = Tokens.Accent, fontSize = 13.sp)
                }
            }
        }

        if (participants.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterPill(label = "All", selected = filter == null, onClick = { onFilterChange(null) })
                participants.forEach { p ->
                    // Label follows the rename; the filter value stays the raw name, which
                    // is what the entries carry.
                    FilterPill(
                        label = displayNameFor(p),
                        selected = filter == p,
                        onClick = { onFilterChange(p) },
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterPill(
                label = "Photos & videos · ${shownMedia.size}",
                selected = tab == 0,
                onClick = { onTabChange(0) },
            )
            FilterPill(
                label = "Voice notes · ${shownVoices.size}",
                selected = tab == 1,
                onClick = { onTabChange(1) },
            )
        }
        Hairline()

        Box(modifier = Modifier.fillMaxSize()) {
        if (tab == 0) {
            if (shownMedia.isEmpty()) {
                EmptyState(
                    "No photos or videos",
                    "Nothing was shared by $${if (filter != null) displayNameFor(filter) else "anyone"} in this chat.",
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(shownMedia.size) { i ->
                        val e = shownMedia[i]
                        GridMedia(
                            path = e.path,
                            isVideo = e.isVideo,
                            index = i,
                            selected = e.path in selected,
                            selectMode = selectMode,
                            onClick = {
                                if (selectMode) toggle(e.path)
                                else if (e.isVideo) onOpenVideo(e) else onOpenImage(e)
                            },
                            onLongPress = { startSelect(e.path) },
                        )
                    }
                }
            }
        } else {
            if (shownVoices.isEmpty()) {
                EmptyState(
                        "No voice notes",
                        "Nothing was sent by $${if (filter != null) displayNameFor(filter) else "anyone"} in this chat.",
                    )
            } else {
                LazyColumn(state = voicesState, modifier = Modifier.fillMaxSize()) {
                    items(shownVoices.size) { i ->
                        val e = shownVoices[i]
                        VoiceLibraryRow(
                            entry = e,
                            controller = controller,
                            selected = e.path in selected,
                            selectMode = selectMode,
                            onGoToChat = { onGoToChat(e.msgIndex) },
                            onLongPress = { startSelect(e.path) },
                            onClick = { if (selectMode) toggle(e.path) },
                            displayNameFor = displayNameFor,
                        )
                    }
                }
            }
        }
    }
}
            // Floating jump-to-top over whichever list is showing, so a long gallery is
            // one tap from the start.
            if (scrolledDown) {
                SmallFab(
                    light = true,
                    onClick = {
                        galleryScope.launch {
                            if (tab == 0) gridState.animateScrollToItem(0)
                            else voicesState.animateScrollToItem(0)
                        }
                    },
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, "Back to top", tint = Tokens.Text)
                }
            }
        }

/**
 * A quiet placeholder shown while a thumbnail decodes, so empty cells do not flash text.
 */
@Composable
private fun ShimmerBox() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.Surface.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Tokens.Line.copy(alpha = 0.8f)),
        )
    }
}

/**
 * The marker that says "this message has a note", showing the note itself.
 *
 * It is both the indicator and the way to read the note without opening Search, so tapping
 * it goes straight back to the editor.
 */
@Composable
private fun NoteStrip(text: String, fg: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (fg == Tokens.OnAccent) Tokens.AccentDim else Tokens.Bg)
            .border(1.dp, Tokens.Gilt, RoundedCornerShape(8.dp))
            .sinkOnPress(LocalMotion.current.press, onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Default.Edit,
            contentDescription = "Note",
            tint = Tokens.Gilt,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            color = fg,
            fontSize = 13.sp,
            fontStyle = FontStyle.Italic,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A circular photo, or a plain initial on a muted disc when no photo is set.
 *
 * Decoded small and downsampled: it sits at 22-44dp, so anything larger is wasted memory.
 */
@Composable
fun AvatarImage(path: String?, size: Dp, modifier: Modifier = Modifier, fallbackInitial: String = "") {
    val shape = CircleShape
    // Cached, like the chat thumbnails. Without this, every visible bubble re-read the file
    // from disk on each scroll, which made long chats visibly slower once avatars appeared.
    val key = path?.let { "avatar:$it" }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val bmp by produceState<ImageBitmap?>(initialValue = key?.let { imageCache.get(it) }, key) {
        if (key == null) {
            value = null
            return@produceState
        }
        imageCache.get(key)?.let {
            value = it
            return@produceState
        }
        val file = path!!
        val decoded = withContext(Dispatchers.IO) {
            val fromDisk = diskCache(ctx).get(file, 144)
            if (fromDisk != null) {
                fromDisk
            } else {
                val fresh = runCatching { decodeScaled(file, 144) }.getOrNull()
                if (fresh != null) diskCache(ctx).put(file, 144, fresh)
                fresh
            }
        }
        if (decoded != null) imageCache.put(key, decoded)
        value = decoded
    }
    val img = bmp
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            // accentDim rather than Surface: on the dark themes a Surface circle is the
            // exact colour of an incoming bubble, so the avatar disappeared into it.
            .background(Tokens.AccentDim)
            .border(1.dp, Tokens.Line, shape),
        contentAlignment = Alignment.Center,
    ) {
        val image = img
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = fallbackInitial.trim().take(1).uppercase(),
                color = Tokens.Muted,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}

/** A pill used for the participant filter and the media/voice tabs. */
@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
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
            maxLines = 1,
        )
    }
}

/** A playable voice note row in the library, with a jump-to-message action. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceLibraryRow(
    entry: VoiceEntry,
    controller: MediaPlayerController,
    selected: Boolean = false,
    selectMode: Boolean = false,
    onGoToChat: () -> Unit,
    onLongPress: () -> Unit,
    onClick: () -> Unit = {},
    /** Raw export name -> the display name this chat uses, so renames show without a rebuild. */
    displayNameFor: (String) -> String = { it },
) {
    val playing = controller.currentKey == entry.path && controller.isPlaying
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (entry.path.isNotBlank() && selected) Tokens.AccentDim else Tokens.Bg)
            .combinedClickable(
                onClick = { if (selectMode) onClick() },
                onLongClick = onLongPress,
            )
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayPauseBadge(
            playing = playing,
            fg = Tokens.OnAccent,
            bg = Tokens.Accent,
            size = 40.dp,
            onClick = { controller.toggle(entry.path, Uri.fromFile(File(entry.path))) },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = displayNameFor(entry.rawSender).ifBlank { "Voice note" },
                color = Tokens.Text,
                fontSize = 15.sp,
                maxLines = 1,
            )
            Text(
                text = entry.time,
                color = Tokens.Muted,
                fontSize = 12.sp,
            )
        }
        TextButton(onClick = onGoToChat) {
            Text("View in chat", color = Tokens.Accent, fontSize = 13.sp)
        }
    }
    Hairline()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridMedia(
    path: String,
    isVideo: Boolean,
    index: Int,
    selected: Boolean = false,
    selectMode: Boolean = false,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val motion = LocalMotion.current
    val dctx = androidx.compose.ui.platform.LocalContext.current
    val bmp by produceState<ImageBitmap?>(initialValue = null, path, isVideo) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (isVideo) {
                    MediaInfo.poster(path, 320)?.asImageBitmap()
                } else {
                    val fromDisk = diskCache(dctx).get(path, 320)
                    if (fromDisk != null) {
                        fromDisk
                    } else {
                        val fresh = decodeScaled(path, 320)
                        if (fresh != null) diskCache(dctx).put(path, 320, fresh)
                        fresh
                    }
                }
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .riseIn(index, motion.listRise)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Tokens.AccentDim else Tokens.Surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Tokens.Accent else Tokens.Line,
                shape = RoundedCornerShape(6.dp),
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        contentAlignment = Alignment.Center,
    ) {
        if (selectMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (selected) Tokens.Accent else Tokens.Bg)
                    .border(1.dp, if (selected) Tokens.Accent else Tokens.Line, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Tokens.OnAccent,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaImage(path: String, onClick: () -> Unit, onLongPress: () -> Unit) {
    val isGif = path.endsWith(".gif", ignoreCase = true)
    val canAnimate = isGif && android.os.Build.VERSION.SDK_INT >= 28
    val key = cacheKey(path, THUMB_PX)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = imageCache.get(key), path, canAnimate) {
        if (canAnimate) return@produceState
        val cached = imageCache.get(key)
        if (cached != null) {
            value = cached
            return@produceState
        }
        val bmp = withContext(Dispatchers.IO) {
            // The disk copy first: re-decoding the original is the expensive path, and it
            // used to happen on every process start.
            diskCache(ctx).get(path, THUMB_PX)
                ?: runCatching { decodeScaled(path, THUMB_PX) }.getOrNull()
        }
        if (bmp != null) {
            imageCache.put(key, bmp)
            withContext(Dispatchers.IO) { diskCache(ctx).put(path, THUMB_PX, bmp) }
        }
        value = bmp
    }
    Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .size(width = 220.dp, height = 165.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Tokens.Bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
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
                // A quiet placeholder rather than the word "Image", which used to flash
                // in every empty cell before the thumbnail arrived.
                ShimmerBox()
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
private fun ImageOverlay(path: String, onClose: () -> Unit, onViewInChat: (() -> Unit)? = null) {
    val image by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodeScaled(path, 2048) }.getOrNull()
        }
    }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val animations = LocalAnimations.current
    val fade by animateFloatAsState(if (shown) 1f else 0f, tween(if (animations) 160 else 0), label = "imageOverlay")

    // Pinch-to-zoom / pan, with double-tap to toggle zoom. Reset whenever the image changes.
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    var boxSize by remember(path) { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset, s: Float): Offset {
        if (s <= 1f) return Offset.Zero
        val maxX = (s - 1f) * boxSize.width / 2f
        val maxY = (s - 1f) * boxSize.height / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset = clamp(offset + panChange, scale)
    }
    fun resetZoom() {
        scale = 1f
        offset = Offset.Zero
    }

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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .onSizeChanged { boxSize = it }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .pointerInput(path) {
                        detectTapGestures(
                            onTap = { onClose() },
                            onDoubleTap = { if (scale > 1f) resetZoom() else scale = 2.5f },
                        )
                    }
                    .transformable(transformState),
            )
        } else {
            Text(text = "Loading…", color = Tokens.Text)
        }
        if (onViewInChat != null) {
            ViewInChatButton(
                onClick = onViewInChat,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp),
            )
        }
    }
}

/** A translucent "View in chat" pill for the full-screen overlays. */
@Composable
internal fun ViewInChatButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(text = "View in chat", color = Color.White, fontSize = 14.sp)
    }
}

/** Decode target for inline thumbnails; small enough to keep scrolling smooth. */
private const val THUMB_PX = 640

private fun cacheKey(path: String, maxPx: Int) = "$path@$maxPx"

/** Byte-bounded cache so re-scrolling a chat does not re-decode the same photos. */
/**
 * Thumbnails, keyed by "<path>@<target px>".
 *
 * Sized as a share of the heap rather than a fixed number, so it adapts to the device
 * instead of being wrong on both small and large phones.
 */
private val imageCache = object : android.util.LruCache<String, ImageBitmap>(cacheBudgetBytes()) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

private fun cacheBudgetBytes(): Int {
    val heapMb = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt()
    return (heapMb / 8).coerceIn(8, 64) * 1024 * 1024
}

/** Survives process death, unlike [imageCache], so reopening a chat stays fast. */
private var thumbDisk: ThumbDiskCache? = null

private fun diskCache(context: android.content.Context): ThumbDiskCache =
    thumbDisk ?: ThumbDiskCache(context).also { thumbDisk = it }

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
