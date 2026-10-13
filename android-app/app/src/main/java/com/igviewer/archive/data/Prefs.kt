package com.igviewer.archive.data

import android.content.Context

/**
 * On-device, app-private preferences for the chosen perspective ("who am I").
 *
 * Only a display name is stored -- no message content, no export paths, nothing
 * from the archive. This never touches the user's export folder; it lives in the
 * app's own private storage.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("igv_prefs", Context.MODE_PRIVATE)

    /** The globally chosen display name, or null when the app should auto-detect. */
    var globalMe: String?
        get() = sp.getString(KEY_GLOBAL_ME, null)
        set(value) {
            sp.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_GLOBAL_ME) else putString(KEY_GLOBAL_ME, value)
            }.apply()
        }

    /** A per-chat override, or null when this chat should fall back to the global choice. */
    fun threadMe(key: String): String? = sp.getString(KEY_THREAD_ME + key, null)

    fun setThreadMe(key: String, name: String) {
        sp.edit().putString(KEY_THREAD_ME + key, name).apply()
    }

    fun clearThreadMe(key: String) {
        sp.edit().remove(KEY_THREAD_ME + key).apply()
    }

    /** Whether short UI sound effects are enabled. */
    var sfxEnabled: Boolean
        get() = sp.getBoolean(KEY_SFX_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_SFX_ENABLED, value).apply()

    /** Selected theme variant id (see ThemeVariant); default is the dark palette. */
    var themeId: String
        get() = sp.getString(KEY_THEME_ID, "midnight") ?: "midnight"
        set(value) = sp.edit().putString(KEY_THEME_ID, value).apply()

    /** Global text scale multiplier applied on top of the system font scale. */
    var textScale: Float
        get() = sp.getFloat(KEY_TEXT_SCALE, 1f)
        set(value) = sp.edit().putFloat(KEY_TEXT_SCALE, value).apply()

    /** Whether message/list rows use tighter vertical padding. */
    var compactRows: Boolean
        get() = sp.getBoolean(KEY_COMPACT_ROWS, false)
        set(value) = sp.edit().putBoolean(KEY_COMPACT_ROWS, value).apply()

    /** Whether UI transitions and fade-in animations play. */
    var animations: Boolean
        get() = sp.getBoolean(KEY_ANIMATIONS, true)
        set(value) = sp.edit().putBoolean(KEY_ANIMATIONS, value).apply()

    /** Whether tapping a video thumbnail starts playback immediately. */
    var autoplayVideos: Boolean
        get() = sp.getBoolean(KEY_AUTOPLAY_VIDEOS, false)
        set(value) = sp.edit().putBoolean(KEY_AUTOPLAY_VIDEOS, value).apply()

    /**
     * The participant whose media/voice notes were last shown in this chat's library,
     * or null for "All". Persisted so the choice survives closing the library and restarts.
     */
    fun galleryFilter(key: String): String? = sp.getString(KEY_GALLERY_FILTER + key, null)

    fun setGalleryFilter(key: String, name: String?) {
        sp.edit().apply {
            if (name.isNullOrBlank()) remove(KEY_GALLERY_FILTER + key)
            else putString(KEY_GALLERY_FILTER + key, name)
        }.apply()
    }

    /** The last library tab shown for this chat (0 = media, 1 = voice notes). */
    fun galleryTab(key: String): Int = sp.getInt(KEY_GALLERY_TAB + key, 0)

    fun setGalleryTab(key: String, tab: Int) = sp.edit().putInt(KEY_GALLERY_TAB + key, tab).apply()

    /** Set once the user has seen (and dismissed) the all-files-access prompt. */
    var allFilesPromptDismissed: Boolean
        get() = sp.getBoolean(KEY_ALLFILES_DISMISSED, false)
        set(value) = sp.edit().putBoolean(KEY_ALLFILES_DISMISSED, value).apply()

    /**
     * Absolute path of the last successfully imported export (a copy in app storage,
     * an extracted zip, or -- when all-files access was granted -- an in-place path).
     * Lets the app reopen the same archive on the next launch instead of starting over.
     */
    var savedExportPath: String?
        get() = sp.getString(KEY_EXPORT_PATH, null)
        set(value) {
            sp.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_EXPORT_PATH) else putString(KEY_EXPORT_PATH, value)
            }.apply()
        }

    private companion object {
        const val KEY_GLOBAL_ME = "global_me"
        const val KEY_THREAD_ME = "thread_me:"
        const val KEY_SFX_ENABLED = "sfx_enabled"
        const val KEY_GALLERY_FILTER = "gallery_filter:"
        const val KEY_GALLERY_TAB = "gallery_tab:"
        const val KEY_ALLFILES_DISMISSED = "allfiles_prompt_dismissed"
        const val KEY_EXPORT_PATH = "export_path"
        const val KEY_THEME_ID = "theme_id"
        const val KEY_TEXT_SCALE = "text_scale"
        const val KEY_COMPACT_ROWS = "compact_rows"
        const val KEY_ANIMATIONS = "animations"
        const val KEY_AUTOPLAY_VIDEOS = "autoplay_videos"
    }
}
