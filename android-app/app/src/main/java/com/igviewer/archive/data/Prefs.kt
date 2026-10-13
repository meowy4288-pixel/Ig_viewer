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
        const val KEY_ALLFILES_DISMISSED = "allfiles_prompt_dismissed"
        const val KEY_EXPORT_PATH = "export_path"
    }
}
