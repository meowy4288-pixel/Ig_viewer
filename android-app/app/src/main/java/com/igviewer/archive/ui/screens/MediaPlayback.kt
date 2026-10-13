package com.igviewer.archive.ui.screens

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Image
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.igviewer.archive.data.MediaInfo
import com.igviewer.archive.ui.theme.LocalAnimations
import com.igviewer.archive.ui.theme.Tokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Drives one ExoPlayer for the whole chat screen. Only one item plays at a time;
 * calling [toggle] with a different key swaps the media item. All ExoPlayer access
 * happens on the main thread, matching the listener callback thread.
 */
class MediaPlayerController(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()

    var currentKey: String? by mutableStateOf(null)
        private set
    var isPlaying: Boolean by mutableStateOf(false)
        private set
    var positionMs: Long by mutableLongStateOf(0L)
        private set
    var durationMs: Long by mutableLongStateOf(0L)
        private set
    var speed: Float by mutableFloatStateOf(1f)
        private set
    var failed: Boolean by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    val d = player.duration
                    durationMs = if (d > 0) d else 0L
                } else if (state == Player.STATE_ENDED) {
                    isPlaying = false
                    positionMs = durationMs
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                failed = true
                isPlaying = false
            }
        })
        ticker = scope.launch {
            while (isActive) {
                if (player.isPlaying) positionMs = player.currentPosition
                delay(200)
            }
        }
    }

    fun toggle(key: String, uri: Uri) {
        if (currentKey == key && !failed) {
            if (player.isPlaying) {
                player.pause()
            } else {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                player.play()
            }
            return
        }
        start(key, uri)
    }

    /** Start [key] from the beginning, or resume it if already current. */
    fun play(key: String, uri: Uri) {
        if (currentKey != key) {
            start(key, uri)
        } else {
            player.playWhenReady = true
        }
    }

    /**
     * Load [key] without playing, so its first frame renders on an attached surface.
     * Used when autoplay is off: the video is ready the moment the user taps play.
     */
    fun prepare(key: String, uri: Uri) {
        if (currentKey != key) start(key, uri, play = false)
    }

    private fun start(key: String, uri: Uri, play: Boolean = true) {
        currentKey = key
        failed = false
        positionMs = 0
        durationMs = 0
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = play
    }

    fun pause() {
        player.pause()
    }

    fun seekToFraction(frac: Float) {
        val d = player.duration
        if (d > 0) player.seekTo((d * frac.coerceIn(0f, 1f)).toLong())
    }

    fun cycleSpeed() {
        speed = when (speed) {
            1f -> 1.5f
            1.5f -> 2f
            2f -> 1f
            else -> 1f
        }
        player.setPlaybackParameters(PlaybackParameters(speed))
    }

    fun release() {
        ticker?.cancel()
        scope.cancel()
        player.release()
    }
}

/** The controller lives as long as the chat screen; leaving the chat releases the player. */
@Composable
fun rememberMediaController(): MediaPlayerController {
    val context = LocalContext.current
    val controller = remember { MediaPlayerController(context) }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    return controller
}

/** A circular play/pause button. When [onClick] is null it is a decorative overlay. */
@Composable
fun PlayPauseBadge(
    playing: Boolean,
    fg: Color,
    bg: Color,
    size: Dp = 34.dp,
    onClick: (() -> Unit)? = null,
) {
    val shape = CircleShape
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(fg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size * 0.42f)) {
            if (playing) {
                val barW = this.size.width * 0.32f
                val gap = this.size.width * 0.36f
                drawRect(color = bg, topLeft = Offset(0f, 0f), size = Size(barW, this.size.height))
                drawRect(color = bg, topLeft = Offset(gap, 0f), size = Size(barW, this.size.height))
            } else {
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(this@Canvas.size.width, this@Canvas.size.height / 2f)
                    lineTo(0f, this@Canvas.size.height)
                    close()
                }
                drawPath(path, color = bg)
            }
        }
    }
}

/** A thin tap-to-seek bar. */
@Composable
private fun SeekBar(frac: Float, enabled: Boolean, onSeek: (Float) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures { off ->
                        if (size.width > 0) onSeek((off.x / size.width).coerceIn(0f, 1f))
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(CircleShape)
                .background(Tokens.Line),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(frac.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(Tokens.Gilt),
            )
        }
    }
}

/** An inline voice-note bubble: play/pause, seek bar, elapsed/total, speed. */
@Composable
fun VoiceNoteBubble(
    path: String,
    fg: Color,
    bg: Color,
    controller: MediaPlayerController,
) {
    val isCurrent = controller.currentKey == path
    val playing = isCurrent && controller.isPlaying
    var probed by remember(path) { mutableStateOf<Long?>(null) }
    LaunchedEffect(path) {
        if (probed == null) probed = withContext(Dispatchers.IO) { MediaInfo.durationMs(path) }
    }
    val duration = if (isCurrent && controller.durationMs > 0) controller.durationMs else probed
    val position = if (isCurrent) controller.positionMs else 0L
    val frac = if (duration != null && duration > 0) {
        (position.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }

    Row(
        modifier = Modifier
            .padding(top = 6.dp)
            .widthIn(min = 190.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayPauseBadge(playing = playing, fg = fg, bg = bg) {
            controller.toggle(path, Uri.fromFile(File(path)))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
        ) {
            SeekBar(frac = frac, enabled = isCurrent && duration != null) { controller.seekToFraction(it) }
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = MediaInfo.formatDuration(if (isCurrent) position else 0L),
                    color = fg.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                )
                Box(modifier = Modifier.weight(1f))
                Text(
                    text = MediaInfo.formatDuration(duration).ifEmpty { "--:--" },
                    color = fg.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                )
            }
        }
        if (isCurrent) {
            Text(
                text = "${speedLabel(controller.speed)}",
                color = fg.copy(alpha = 0.8f),
                fontSize = 12.sp,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(fg.copy(alpha = 0.14f))
                    .clickable { controller.cycleSpeed() }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

private fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

/** A video thumbnail: poster frame, play glyph and a duration badge; tap to open. */
@Composable
fun VideoThumb(
    path: String,
    fg: Color,
    onClick: () -> Unit,
) {
    val poster by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { MediaInfo.poster(path, 640)?.asImageBitmap() }.getOrNull()
        }
    }
    var duration by remember(path) { mutableStateOf<Long?>(null) }
    LaunchedEffect(path) {
        duration = withContext(Dispatchers.IO) { MediaInfo.durationMs(path) }
    }

    Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .size(width = 240.dp, height = 180.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Tokens.Bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val img = poster
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(text = "Video", color = Tokens.Muted, fontSize = 13.sp)
        }
        PlayPauseBadge(
            playing = false,
            fg = Color.White,
            bg = Color.Black.copy(alpha = 0.55f),
        )
        if (duration != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = MediaInfo.formatDuration(duration),
                    color = Color.White,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/** A full-screen video player backed by its own ExoPlayer. */
@Composable
fun VideoOverlay(path: String, onClose: () -> Unit, autoplay: Boolean = false) {
    val context = LocalContext.current
    val controller = remember { MediaPlayerController(context) }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    LaunchedEffect(path, autoplay) {
        val uri = Uri.fromFile(File(path))
        // Always prepare so the first frame paints; only start playing if asked.
        controller.prepare(path, uri)
        if (autoplay) controller.play(path, uri)
    }

    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val animations = LocalAnimations.current
    val fade by animateFloatAsState(if (shown) 1f else 0f, tween(if (animations) 160 else 0), label = "videoOverlay")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(fade)
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).also { controller.player.setVideoSurfaceView(it) }
            },
            modifier = Modifier.fillMaxSize(),
        )

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Close video",
                tint = Color.White,
            )
        }

        if (controller.failed) {
            Text(
                text = "Can't play this video",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            PlayPauseBadge(
                playing = controller.isPlaying,
                fg = Color.White,
                bg = Color.Black.copy(alpha = 0.5f),
                size = 56.dp,
                onClick = {
                    controller.toggle(path, Uri.fromFile(File(path)))
                },
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            val dur = controller.durationMs
            val frac = if (dur > 0) (controller.positionMs.toFloat() / dur).coerceIn(0f, 1f) else 0f
            SeekBar(frac = frac, enabled = dur > 0) { controller.seekToFraction(it) }
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = MediaInfo.formatDuration(controller.positionMs),
                    color = Color.White,
                    fontSize = 12.sp,
                )
                Box(modifier = Modifier.weight(1f))
                Text(
                    text = MediaInfo.formatDuration(dur),
                    color = Color.White,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
