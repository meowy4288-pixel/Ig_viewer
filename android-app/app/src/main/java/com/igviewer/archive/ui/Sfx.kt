package com.igviewer.archive.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.staticCompositionLocalOf
import com.igviewer.archive.R

/**
 * Short UI sound effects, played through a single [SoundPool].
 *
 * SoundPool is the right tool for brief samples: it predecodes into memory and
 * plays with very low latency, unlike MediaPlayer. Everything is bundled in the
 * APK (res/raw) -- no network, no new permissions. The user controls it from
 * Settings; [enabled] and [volume] are kept in sync with the saved preference.
 */
class Sfx(context: Context) {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            // USAGE_MEDIA routes to STREAM_MUSIC ("normal" volume), so effects follow
            // the media volume rocker the user already expects, not the ring stream.
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val tapId: Int = pool.load(context, R.raw.sfx_tap, 1)

    var enabled: Boolean = true
    var volume: Float = 0.7f

    /** A soft tick for taps on rows and buttons. */
    fun tap() {
        if (!enabled) return
        pool.play(tapId, volume, volume, 1, 0, 1f)
    }

    fun release() {
        pool.release()
    }
}

/** Provided once at the app root so any screen can trigger feedback. */
val LocalSfx = staticCompositionLocalOf<Sfx> { error("No Sfx provided") }
