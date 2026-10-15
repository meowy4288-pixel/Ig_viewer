package com.igviewer.archive.data

import java.io.File

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

    // Per-effect motion toggles. Each is additionally gated by [animations]; these
    // only let one effect be turned off while the rest keep moving.
    var animScreen: Boolean
        get() = sp.getBoolean(KEY_ANIM_SCREEN, true)
        set(value) = sp.edit().putBoolean(KEY_ANIM_SCREEN, value).apply()

    var animThemeFade: Boolean
        get() = sp.getBoolean(KEY_ANIM_THEME_FADE, true)
        set(value) = sp.edit().putBoolean(KEY_ANIM_THEME_FADE, value).apply()

    var animPress: Boolean
        get() = sp.getBoolean(KEY_ANIM_PRESS, true)
        set(value) = sp.edit().putBoolean(KEY_ANIM_PRESS, value).apply()

    var animListRise: Boolean
        get() = sp.getBoolean(KEY_ANIM_LIST_RISE, true)
        set(value) = sp.edit().putBoolean(KEY_ANIM_LIST_RISE, value).apply()

    var animCascade: Boolean
        get() = sp.getBoolean(KEY_ANIM_CASCADE, true)
        set(value) = sp.edit().putBoolean(KEY_ANIM_CASCADE, value).apply()

    var animDateTick: Boolean
        get() = sp.getBoolean(KEY_ANIM_DATE_TICK, true)
        set(value) = sp.edit().putBoolean(KEY_ANIM_DATE_TICK, value).apply()

    var animFlare: Boolean
        get() = sp.getBoolean(KEY_ANIM_FLARE, true)
        set(value) = sp.edit().putBoolean(KEY_ANIM_FLARE, value).apply()

    var animJump: Boolean
        get() = sp.getBoolean(KEY_ANIM_JUMP, true)
        set(value) = sp.edit().putBoolean(KEY_ANIM_JUMP, value).apply()

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
    /**
     * Folders that hold media referenced by the chats but sit outside every import root
     * (a common split export). Used for media lookup only: they can never add chats.
     */
    var mediaRoots: List<File>
        get() = sp.getString(KEY_MEDIA_ROOTS, "")?.split('\n')?.filter { it.isNotBlank() }
            ?.map { File(it) }?.filter { it.isDirectory } ?: emptyList()
        set(value) {
            sp.edit().apply {
                val live = value.map { it.absolutePath }.filter { File(it).isDirectory }
                if (live.isEmpty()) remove(KEY_MEDIA_ROOTS)
                else putString(KEY_MEDIA_ROOTS, live.joinToString("\n"))
            }.apply()
        }

    /**
     * Every export root ever imported, accumulated. An import ADDS to this list rather than
     * replacing it, so importing a second archive keeps the chats from the first.
     */
    var importedRoots: List<String>
        get() = sp.getString(KEY_IMPORTED_ROOTS, "")?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(value) {
            sp.edit().apply {
                if (value.isEmpty()) remove(KEY_IMPORTED_ROOTS)
                else putString(KEY_IMPORTED_ROOTS, value.joinToString("\n"))
            }.apply()
        }

    /**
     * Fingerprints (name + size + modified time) of already-imported items, so picking the
     * same file again is rejected as a duplicate instead of being copied a second time.
     */
    var importedFingerprints: List<String>
        get() = sp.getString(KEY_IMPORTED_FINGERPRINTS, "")?.split('\u001f')?.filter { it.isNotBlank() }
            ?: emptyList()
        set(value) {
            sp.edit().apply {
                if (value.isEmpty()) remove(KEY_IMPORTED_FINGERPRINTS)
                else putString(KEY_IMPORTED_FINGERPRINTS, value.joinToString("\u001f"))
            }.apply()
        }

    /** Every root that makes up the current archive; a plain import has exactly one. */
    var savedExportPaths: List<String>
        get() = sp.getString(KEY_EXPORT_PATHS, "")?.split('\n')?.filter { it.isNotBlank() }
            ?: emptyList()
        set(value) {
            sp.edit().apply {
                if (value.isEmpty()) remove(KEY_EXPORT_PATHS) else putString(KEY_EXPORT_PATHS, value.joinToString("\n"))
            }.apply()
        }

    var savedExportPath: String?
        get() = sp.getString(KEY_EXPORT_PATH, null)
        set(value) {
            sp.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_EXPORT_PATH) else putString(KEY_EXPORT_PATH, value)
            }.apply()
        }

    /**
     * Tree URI of the folder "Download" writes into, chosen by the user with the system
     * folder picker. Stored together with its display name so Settings can show it.
     */
    var downloadTreeUri: String?
        get() = sp.getString(KEY_DOWNLOAD_TREE, null)
        set(value) {
            sp.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_DOWNLOAD_TREE) else putString(KEY_DOWNLOAD_TREE, value)
            }.apply()
        }

    var downloadTreeName: String?
        get() = sp.getString(KEY_DOWNLOAD_TREE_NAME, null)
        set(value) {
            sp.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_DOWNLOAD_TREE_NAME) else putString(KEY_DOWNLOAD_TREE_NAME, value)
            }.apply()
        }

    /**
     * Display names the user has given to people, scoped to ONE chat.
     *
     * Per chat rather than global on purpose: "sister" can be "Ana" in one conversation
     * and something else in another, and a global list is unmanageable once you have more
     * than a handful of chats. Never written back to the export.
     */
    fun renamed(chatKey: String, original: String): String? =
        sp.getString(KEY_RENAME + chatKey + "\u0000" + original, null)

    /** Blank, or unchanged, removes the override so the export's spelling comes back. */
    fun setRenamed(chatKey: String, original: String, display: String) {
        val key = KEY_RENAME + chatKey + "\u0000" + original
        val clean = display.trim()
        sp.edit().apply {
            if (clean.isEmpty() || clean == original) remove(key) else putString(key, clean)
        }.apply()
    }

    /**
     * Photo for one person in one chat, keyed the same way as the rename.
     *
     * Deliberately keyed by the person's EXPORT name and not by "who is you": flipping the
     * perspective moves messages across the screen but must never move someone's photo with
     * them.
     */
    /**
     * The stored path for this person's photo in this chat, or null.
     *
     * Deliberately does NOT stat the file: this is called once per participant per chat at
     * boot, and a syscall there adds up to hundreds on a large archive. A stale path is
     * self-healing -- the avatar decoder fails and the initial is drawn instead.
     */
    fun contactAvatar(chatKey: String, original: String): String? =
        sp.getString(KEY_CONTACT_AVATAR + chatKey + "\u0000" + original, null)
            ?.takeIf { it.isNotBlank() }

    fun setContactAvatar(chatKey: String, original: String, path: String?) {
        val key = KEY_CONTACT_AVATAR + chatKey + "\u0000" + original
        sp.edit().apply {
            if (path.isNullOrBlank()) remove(key) else putString(key, path)
        }.apply()
    }

    /**
     * Every note the user wrote, as a JSON array (see [NoteStore]). Small enough to keep
     * in prefs; nothing chat-derived is ever written to it.
     */
    var notesJson: String
        get() = sp.getString(KEY_NOTES, "") ?: ""
        set(value) = sp.edit().putString(KEY_NOTES, value).apply()

    private companion object {
        const val KEY_GLOBAL_ME = "global_me"
        const val KEY_THREAD_ME = "thread_me:"
        const val KEY_SFX_ENABLED = "sfx_enabled"
        const val KEY_GALLERY_FILTER = "gallery_filter:"
        const val KEY_GALLERY_TAB = "gallery_tab:"
        const val KEY_ALLFILES_DISMISSED = "allfiles_prompt_dismissed"
        const val KEY_EXPORT_PATH = "export_path"
        const val KEY_EXPORT_PATHS = "export_paths"
        const val KEY_MEDIA_ROOTS = "media_roots"
        const val KEY_IMPORTED_ROOTS = "imported_roots"
        const val KEY_IMPORTED_FINGERPRINTS = "imported_fingerprints"
        const val KEY_DOWNLOAD_TREE = "download_tree"
        const val KEY_DOWNLOAD_TREE_NAME = "download_tree_name"
        const val KEY_NOTES = "notes"
        const val KEY_RENAME = "rename:"
        const val KEY_AVATAR = "avatar_path"
        const val KEY_CONTACT_AVATAR = "contact_avatar:"
        const val KEY_THEME_ID = "theme_id"
        const val KEY_TEXT_SCALE = "text_scale"
        const val KEY_COMPACT_ROWS = "compact_rows"
        const val KEY_ANIMATIONS = "animations"
        const val KEY_AUTOPLAY_VIDEOS = "autoplay_videos"
        const val KEY_ANIM_SCREEN = "anim_screen"
        const val KEY_ANIM_THEME_FADE = "anim_theme_fade"
        const val KEY_ANIM_PRESS = "anim_press"
        const val KEY_ANIM_LIST_RISE = "anim_list_rise"
        const val KEY_ANIM_CASCADE = "anim_cascade"
        const val KEY_ANIM_DATE_TICK = "anim_date_tick"
        const val KEY_ANIM_FLARE = "anim_flare"
        const val KEY_ANIM_JUMP = "anim_jump"
    }
}
