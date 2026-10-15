package com.igviewer.archive

import com.igviewer.archive.data.formatBytes
import com.igviewer.archive.ui.theme.Tokens
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.igviewer.archive.data.ArchiveIndex
import com.igviewer.archive.data.ArchivePicker
import com.igviewer.archive.data.ImportProgress
import com.igviewer.archive.data.NoteStore
import com.igviewer.archive.data.Prefs
import com.igviewer.archive.data.UiMapping
import com.igviewer.archive.ui.NavState
import com.igviewer.archive.ui.Screen
import com.igviewer.archive.ui.Sfx
import com.igviewer.archive.ui.components.WhoAmIDialog
import com.igviewer.archive.ui.screens.ChatListScreen
import com.igviewer.archive.ui.screens.ChatScreen
import com.igviewer.archive.ui.screens.ImportScreen
import com.igviewer.archive.ui.screens.MessageItem
import com.igviewer.archive.ui.screens.NoteResult
import com.igviewer.archive.ui.screens.SearchScreen
import com.igviewer.archive.ui.screens.SettingsScreen
import com.igviewer.archive.ui.theme.AppSettings
import com.igviewer.archive.ui.theme.IGArchiveTheme
import com.igviewer.archive.ui.theme.LocalAnimations
import com.igviewer.archive.ui.theme.MOTION_ENABLED
import com.igviewer.archive.ui.theme.LocalCompactRows
import com.igviewer.archive.ui.theme.LocalMotion
import com.igviewer.archive.ui.theme.Motion
import com.igviewer.archive.ui.theme.veil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File


/** First slice published immediately on a chat open, then regular slices after that. */
private const val FIRST_CHUNK = 250
private const val CHUNK = 400

class MainActivity : ComponentActivity() {
    private lateinit var picker: ArchivePicker
    private lateinit var prefs: Prefs
    private var pendingUri: Uri? = null
    private var pendingUris: List<Uri> = emptyList()

    /**
     * Exactly one pending selection at a time. Picking zips clears a folder choice and
     * vice versa: an import always replaces the previous one, so importing a second
     * export replaces your chats rather than silently merging into them.
     */
    private val pendingFolders = mutableListOf<Uri>()
    private var pendingIsFolder: Boolean = false

    private var onRenamed: ((String) -> Unit)? = null

    /** Persisted index snapshot, rebuilt only when missing (see AppRoot's restore). */
    private val snapshotFile: File get() = File(filesDir, "imports/index.snapshot")

    private val indexState: MutableState<ArchiveIndex?> = mutableStateOf(null)
    private val importStatus: MutableState<String?> = mutableStateOf(null)
    private val importProgress: MutableState<ImportProgress?> = mutableStateOf(null)

    /** Several exports may be picked at once; the system picker gives long-press multi-select. */
    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val list = uris.filterNotNull()
        if (list.isEmpty()) return@registerForActivityResult
        pendingUris = list
        pendingFolders.clear()
        // runImport() bails out immediately when pendingUri is null, so the single-file
        // slot still has to point at the first item of the selection.
        pendingUri = list.first()
        pendingIsFolder = false
        onRenamed?.invoke(if (list.size == 1) "export.zip" else "${list.size} archives")
    }

    private val pickDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            pendingUris = emptyList()
            if (!pendingFolders.contains(it)) pendingFolders.add(it)
            pendingUri = it
            pendingIsFolder = true
            val n = pendingFolders.size
            onRenamed?.invoke(if (n == 1) "export" else "$n folders")
        }
    }

    /**
     * Folder picker for the download destination. Kept separate from [pickDir] so choosing a
     * download folder can never be mistaken for choosing an export to import.
     */
    private val pickDownloadDir =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                try {
                    contentResolver.takePersistableUriPermission(
                        it,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                } catch (_: SecurityException) {
                    // Not all providers offer a persistable grant; the tree still works this session.
                }
                val name = runCatching {
                    DocumentsContract.getTreeDocumentId(it).substringAfterLast(':').substringAfterLast('/')
                }.getOrNull()
                prefs.downloadTreeUri = it.toString()
                prefs.downloadTreeName = name ?: "Chosen folder"
            }
        }

    private var avatarVersion by mutableIntStateOf(0)

    private val askDownloadFolder: () -> Unit = { pickDownloadDir.launch(null) }

    /**
     * Adds a folder that holds media referenced by the chats but lives outside every
     * import root. Resolved to a real path where possible; otherwise a copy is made so the
     * grant being revoked cannot break media that was already imported.
     */
    private val pickMediaFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch(Dispatchers.IO) {
                val direct = if (hasAllFilesAccess()) picker.directPath(uri) else null
                val path = direct?.absolutePath ?: runCatching {
                    picker.copyToAppStorage(uri, true, "media-" + (prefs.mediaRoots.size + 1)) { null }
                }.getOrNull()
                if (path != null) {
                    prefs.mediaRoots = prefs.mediaRoots + File(path)
                    rebuildIndex()
                } else {
                    importStatus.value = "Could not read that folder"
                }
            }
        }

    /** Re-indexes every imported root plus the extra media folders, in place. */
    private fun rebuildIndex() {
        val roots = prefs.importedRoots.ifEmpty { prefs.savedExportPaths }.map { File(it) }
        if (roots.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val idx = runCatching {
                ArchiveIndex.indexAll(roots, prefs.mediaRoots)
            }.getOrNull() ?: return@launch
            if (idx.threads.isNotEmpty()) {
                runCatching { idx.saveSnapshot(snapshotFile) }
                withContext(Dispatchers.Main) { indexState.value = idx }
            }
        }
    }

    /**
     * Picks the photo shown on your own messages. The chosen image is COPIED into app
     * storage rather than referenced by URI, so the avatar survives the temporary grant
     * being revoked and never depends on the original file still existing.
     */
    /** The chat a picked photo belongs to. Null means the default photo for you. */
    private var avatarChatKey: String? = null

    /** The person's raw export name within that chat. Null means the default photo. */
    private var avatarTarget: String? = null

    private fun storeAvatar(
        uri: android.net.Uri,
        chatKey: String?,
        target: String?,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ext = (contentResolver.getType(uri) ?: "image/jpeg").substringAfterLast('/')
                .takeIf { it.isNotBlank() && it.length <= 5 } ?: "jpeg"
            runCatching {
                // Contact photos are keyed by a hash of the raw name so the filename is
                // always safe and never contains a path separator.
                val dir = if (target == null) filesDir else filesDir.resolve("avatars")
                dir.mkdirs()
                val stem = if (target == null) {
                    "avatar"
                } else {
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(target.toByteArray())
                        .joinToString("") { "%02x".format(it) }
                        .take(24)
                }
                val dest = dir.resolve("$stem.$ext")
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                } ?: error("Could not open that image")
                if (chatKey != null && target != null) {
                    prefs.setContactAvatar(chatKey, target, dest.absolutePath)
                }
                withContext(Dispatchers.Main) { avatarVersion++ }
            }.onFailure {
                importStatus.value = "Could not read that image"
            }
        }
    }

    private val pickAvatar =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            storeAvatar(uri, avatarChatKey, avatarTarget)
        }

    private fun displayName(uri: Uri): String? =
        runCatching {
            contentResolver
                .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()

    /**
     * Identifies a picked item well enough to reject an accidental re-import of the same
     * file. Name alone is not enough (two exports can share a name); size and modified
     * time together make a collision vanishingly unlikely.
     */
    private fun fingerprint(uri: Uri): String {
        val name = displayName(uri) ?: uri.lastPathSegment.orEmpty()
        var size = 0L
        var modified = 0L
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use
                val i = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (i >= 0 && !c.isNull(i)) size = c.getLong(i)
                val m = c.getColumnIndex(
                    android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                )
                if (m >= 0 && !c.isNull(m)) modified = c.getLong(m)
            }
        }
        return "$name|$size|$modified"
    }

    /** What an import attempt produced. */
    private sealed interface ImportOutcome {
        data class Indexed(val index: ArchiveIndex, val roots: List<String>) : ImportOutcome
        data class Nothing(val pickedCount: Int) : ImportOutcome
    }

    private fun runImport(onDone: (Boolean) -> Unit) {
        val uri = pendingUri ?: run {
            // Nothing was actually picked (or the picker was cancelled).
            importStatus.value = "Nothing was selected."
            onDone(false)
            return
        }
        val isFolder = pendingIsFolder
        importStatus.value = null
        importProgress.value = null
        lifecycleScope.launch {
            runCatching {
                // Fingerprint everything picked this time, then keep only the new items.
                val picked = withContext(Dispatchers.IO) {
                    val folders = pendingFolders.map { Triple(fingerprint(it), it, true) }
                    val zips = pendingUris.map { Triple(fingerprint(it), it, false) }
                    val all = folders + zips
                    val seen = prefs.importedFingerprints.toMutableSet()
                    all.filter { seen.add(it.first) } to all.size
                }
                val (fresh, pickedCount) = picked
                if (fresh.isEmpty()) {
                    // Every item was already imported. Do nothing at all and leave the
                    // existing archive exactly as it is.
                    return@runCatching ImportOutcome.Nothing(pickedCount)
                }

                val newPaths = withContext(Dispatchers.IO) {
                    fresh.mapIndexed { i, (_, u, folder) ->
                        val slug = (displayName(u) ?: "item$i")
                            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
                            .take(48).ifBlank { "item$i" }
                        if (folder) {
                            // Read in place only with all-files access; otherwise copy.
                            val direct = if (hasAllFilesAccess()) picker.directPath(u) else null
                            direct?.absolutePath
                                ?: picker.copyToAppStorage(u, true, "import-$slug") { importProgress.value = it }
                        } else {
                            val dest = picker.exportDir().resolve("import-$slug").also { it.mkdirs() }
                            picker.extractZip(u, dest) { importProgress.value = it }
                            dest.absolutePath
                        }
                    }
                }

                // ADD to what is already imported; never replace it.
                val allRoots = (prefs.importedRoots + newPaths).distinct()
                prefs.importedRoots = allRoots
                prefs.importedFingerprints = (fresh.map { it.first } + prefs.importedFingerprints).distinct()

                val roots = allRoots.map { File(it) }
                withContext(Dispatchers.IO) {
                    val idx = ArchiveIndex.indexAll(roots, prefs.mediaRoots) { done, total ->
                        importProgress.value = ImportProgress(
                            phase = ImportProgress.Phase.INDEXING,
                            fraction = if (total > 0) done.toFloat() / total else null,
                            doneItems = done,
                            totalItems = total,
                        )
                    }
                    if (idx.threads.isNotEmpty()) {
                        runCatching { idx.saveSnapshot(snapshotFile) }
                    }
                    ImportOutcome.Indexed(idx, allRoots)
                }
            }.onSuccess { outcome ->
                when (outcome) {
                    is ImportOutcome.Nothing -> {
                        importStatus.value = if (outcome.pickedCount == 1) {
                            "Already imported — nothing to do."
                        } else {
                            "All ${outcome.pickedCount} items were already imported — nothing to do."
                        }
                        onDone(false)
                        return@onSuccess
                    }
                    is ImportOutcome.Indexed -> Unit
                }
                val index = outcome.index
                val path = outcome.roots
                indexState.value = index
                val skipped = index.skippedFiles + index.parseFailures
                if (index.threads.isEmpty()) {
                    val reasons = index.skipReasons.entries
                        .sortedByDescending { it.value }
                        .joinToString(", ") { "${it.key.code} x${it.value}" }
                    importStatus.value = buildString {
                        append("No chats found")
                        if (skipped > 0) append(" — $skipped message files skipped")
                        if (reasons.isNotEmpty()) append(" ($reasons)")
                        append(". Expected your_instagram_activity/messages/inbox/<thread>/message_1.json inside it.")
                    }
                    onDone(false)
                    return@onSuccess
                }
                val msg = buildString {
                    append("Imported ${index.threads.size} chats")
                    if (skipped > 0) append(" · $skipped files skipped")
                    if (path.size > 1) append(" · ${path.size} exports")
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                // Remember where the archive lives so the next launch reopens it.
                prefs.savedExportPaths = path
                onDone(true)
            }.onFailure { e ->
                importStatus.value = "Import failed: ${e.message}"
                onDone(false)
            }
            importProgress.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        picker = ArchivePicker(this)
        prefs = Prefs(this)

        // Fast path: the index manifest is tiny (a few KB), so read it on the main
        // thread before the first frame. When it succeeds the app opens directly onto
        // the chat list -- no "opening" splash at all. Only if it is missing, oversized
        // or unreadable does AppRoot's async restore (with the splash) take over.
        val snapshot = snapshotFile
        if (snapshot.exists() && snapshot.length() <= SYNC_SNAPSHOT_MAX_BYTES) {
            runCatching { ArchiveIndex.loadSnapshot(snapshot) }
                .getOrNull()
                ?.takeIf { it.threads.isNotEmpty() }
                ?.let {
                    indexState.value = it
                }
        }

        val settings = AppSettings(prefs)
        setContent {
            // Each motion effect is independently toggleable, but all are gated by the
            // master "Animations" switch, so turning it off still stops everything.
            val master = MOTION_ENABLED && settings.animations
            val motion = Motion(
                screen = master && settings.animScreen,
                themeFade = master && settings.animThemeFade,
                press = master && settings.animPress,
                listRise = master && settings.animListRise,
                cascade = master && settings.animCascade,
                dateTick = master && settings.animDateTick,
                flare = master && settings.animFlare,
                jump = master && settings.animJump,
            )
            CompositionLocalProvider(
                LocalAnimations provides master,
                LocalMotion provides motion,
            ) {
                IGArchiveTheme(variant = settings.theme, crossfade = motion.themeFade) {
                    val base = LocalDensity.current
                    CompositionLocalProvider(
                        LocalCompactRows provides settings.compactRows,
                        LocalDensity provides Density(base.density, base.fontScale * settings.textScale),
                    ) {
                        AppRoot(
                            indexState = indexState,
                            importStatus = importStatus,
                            importProgress = importProgress,
                            prefs = prefs,
                            settings = settings,
                            launchZipPicker = { cb -> onRenamed = cb; pickZip.launch(arrayOf("application/zip")) },
                            launchDirPicker = { cb -> onRenamed = cb; pickDir.launch(null) },
                            runImport = ::runImport,
                            onGrantAccess = ::requestAllFilesAccess,
                            hasAllFilesAccess = ::hasAllFilesAccess,
                            onDeleteIndex = ::deleteIndex,
                            askDownloadFolder = { askDownloadFolder() },
                            mediaRoots = prefs.mediaRoots.map { it.absolutePath },
                            askMediaFolder = { pickMediaFolder.launch(null) },
                            removeMediaFolder = { p ->
                                prefs.mediaRoots = prefs.mediaRoots.filterNot { it.absolutePath == p }
                                rebuildIndex()
                            },
                            pickAvatarLauncher = { chatKey, target ->
                                avatarChatKey = chatKey
                                avatarTarget = target
                                pickAvatar.launch(arrayOf("image/*"))
                            },
                            avatarVersion = avatarVersion,
                        )
                    }
                }
            }
        }
    }

    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName"),
                    )
                )
            }.onFailure {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }

    private fun deleteIndex() {
        indexState.value = null
        prefs.savedExportPaths = emptyList()
        prefs.savedExportPath = null
        // Forget which files were imported, otherwise re-importing the same ones would
        // be rejected as duplicates after the data was deleted.
        prefs.importedRoots = emptyList()
        prefs.importedFingerprints = emptyList()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                File(filesDir, "imports").deleteRecursively()
            }
        }
    }

    private companion object {
        /** Snapshots at or below this size are read synchronously before the first frame. */
        const val SYNC_SNAPSHOT_MAX_BYTES = 512 * 1024L
    }
}

@Composable
private fun AppRoot(
    indexState: MutableState<ArchiveIndex?>,
    importStatus: MutableState<String?>,
    importProgress: MutableState<ImportProgress?>,
    prefs: Prefs,
    settings: AppSettings,
    launchZipPicker: ((String) -> Unit) -> Unit,
    launchDirPicker: ((String) -> Unit) -> Unit,
    runImport: ((Boolean) -> Unit) -> Unit,
    onGrantAccess: () -> Unit,
    hasAllFilesAccess: () -> Boolean,
    onDeleteIndex: () -> Unit,
    askDownloadFolder: () -> Unit,
    mediaRoots: List<String>,
    askMediaFolder: () -> Unit,
    removeMediaFolder: (String) -> Unit,
    pickAvatarLauncher: (String?, String?) -> Unit,
    avatarVersion: Int,
) {
    val nav = remember { NavState(if (indexState.value != null) Screen.LIST else Screen.IMPORT) }
    // Bumped whenever a note is written or deleted, so the Notes tab re-reads the store.
    var notesVersion by remember { mutableIntStateOf(0) }
    // Bumped when a contact is renamed, so lists and open chats re-map their names.
    var renamesVersion by remember { mutableIntStateOf(0) }
    val index = indexState.value
    // Renames and photos are per chat, so the mapper is handed lookups rather than a map.
    val context = LocalContext.current
    val sfx = remember { Sfx(context) }
    DisposableEffect(sfx) { onDispose { sfx.release() } }
    SideEffect { sfx.enabled = settings.sfxEnabled }
    val animations = LocalAnimations.current
    val motion = LocalMotion.current

    var lightboxVisible by remember { mutableStateOf(false) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var indexing by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var targetMsgIndex by remember { mutableStateOf<Int?>(null) }
    var chatMessages by remember { mutableStateOf<List<MessageItem>>(emptyList()) }

    // Perspective: a global "me" plus optional per-chat overrides, persisted on device.
    var selfGlobal by remember { mutableStateOf(prefs.globalMe) }
    val perThreadMe = remember { mutableStateMapOf<String, String>() }
    var globalChooser by remember { mutableStateOf(false) }
    var threadChooserKey by remember { mutableStateOf<String?>(null) }

    // Offer the all-files access prompt up front. It is genuinely optional, so the
    // copy says so; dismissing it is remembered so we never nag.
    var askAllFiles by remember {
        mutableStateOf(!prefs.allFilesPromptDismissed && !hasAllFilesAccess())
    }

    fun effectiveMe(key: String?): String? {
        if (key == null) return selfGlobal
        return perThreadMe[key] ?: prefs.threadMe(key) ?: selfGlobal
    }

    fun hasOverride(key: String): Boolean =
        perThreadMe.containsKey(key) || prefs.threadMe(key) != null

    val chatSelf = effectiveMe(selectedKey)

    // Keyed on the avatar version too: setting a photo used to leave the list stale,
    // which is why the chat list kept showing an initial after a photo was chosen.
    val chats = remember(index, renamesVersion, avatarVersion) {
        index?.let {
            UiMapping.chatItems(
                it,
                renameOf = { chatKey, name -> prefs.renamed(chatKey, name) ?: name },
                // Excludes you outright: a DM row must show the other person's photo, so
                // there is deliberately no "anybody with a photo" fallback here.
                avatarOf = { chatKey, name ->
                    val me = perThreadMe[chatKey] ?: prefs.threadMe(chatKey) ?: selfGlobal
                    val detected = index.quickSelf(chatKey)
                    val you = if (me == detected || index.thread(chatKey)?.participants?.contains(me) == true) {
                        me
                    } else {
                        detected
                    }
                    if (name == you) null else prefs.contactAvatar(chatKey, name)
                },
                selfOf = { chatKey ->
                    // quickSelf, NOT detectMe: detectMe deserialises the whole thread.
                    val detected = index.quickSelf(chatKey)
                    val chosen = perThreadMe[chatKey] ?: prefs.threadMe(chatKey) ?: selfGlobal
                    when {
                        chosen == null -> detected
                        chosen == detected -> chosen
                        index.thread(chatKey)?.participants?.contains(chosen) == true -> chosen
                        else -> detected?.takeIf { it.isNotBlank() } ?: chosen
                    }
                },
            )
        } ?: emptyList()
    }


    // System/gesture back mirrors the in-app back arrow. When there is nowhere to go
    // (import screen, or the chat list root) the handler stays disabled, so the system
    // default of finishing the activity applies.
    BackHandler(enabled = nav.canGoBack) { nav.back() }

    // Deliberately NOT keyed on renamesVersion: a rename changes display strings only, and
    // rebuilding every message for it made a rename re-render the whole chat.
    LaunchedEffect(index, selectedKey, chatSelf, avatarVersion) {
        val key = selectedKey
        val idx = index
        if (idx != null && key != null) {
            val renameOf: (String, String) -> String = { c, n -> prefs.renamed(c, n) ?: n }
            val avatarOf: (String, String) -> String? = { c, n -> prefs.contactAvatar(c, n) }
            val msgs = withContext(Dispatchers.IO) { idx.messages(key) }

            // Progressive: map the first slice, publish it so the screen is usable and the
            // header actions live, then keep going. Media resolution dominates this cost, so
            // doing it a slice at a time is what actually makes the chat open feel instant.
            val total = msgs.size
            if (total <= FIRST_CHUNK) {
                chatMessages = withContext(Dispatchers.Default) {
                    UiMapping.messageItems(idx, key, chatSelf, renameOf, avatarOf, source = msgs)
                }
                return@LaunchedEffect
            }
            val acc = ArrayList<MessageItem>(total)
            var day = ""
            var from = 0
            while (from < total) {
                val count = if (from == 0) FIRST_CHUNK else CHUNK
                val (items, after) = withContext(Dispatchers.Default) {
                    UiMapping.messageChunk(idx, key, msgs, from, count, chatSelf, renameOf, avatarOf, day)
                }
                acc.addAll(items)
                day = after
                from += count
                if (from <= FIRST_CHUNK) {
                    // Publish exactly once, so the chat becomes usable immediately...
                    chatMessages = ArrayList(acc)
                    yield()
                }
            }
            // ...then swap in the complete conversation. Deliberately a SINGLE further
            // assignment: reassigning repeatedly made the screen re-run its per-chat
            // effects on every publish, which read as the chat reloading over and over.
            if (acc.size == total) {
                chatMessages = ArrayList(acc)
            }
        } else {
            chatMessages = emptyList()
        }
    }

    // After a fresh import, offer the global chooser once when the perspective is
    // genuinely ambiguous (more than one candidate and no saved choice).
    fun maybeAskGlobal() {
        val idx = indexState.value ?: return
        if (selfGlobal == null && idx.allParticipantNames().size >= 2) globalChooser = true
    }

    /**
     * Runs the import for whatever was just picked and, on success, goes to the chat list.
     * Used by the Settings import buttons so they behave exactly like the home screen.
     */
    fun pickAndIndex() {
        indexing = true
        runImport { ok ->
            indexing = false
            if (ok) {
                nav.resetTo(Screen.LIST)
                maybeAskGlobal()
            }
        }
    }

    // Set when Settings started an import, so the Import screen kicks it off on arrival.
    var autoImport by remember { mutableStateOf(false) }

    /**
     * Settings has no progress bar or status banner of its own, so an import started from
     * there used to run invisibly. It now hands off to the Import screen, which shows both,
     * and starts itself once that screen is up.
     */
    fun importFromSettings(launch: (((String) -> Unit)) -> Unit) {
        launch { name ->
            pickedName = name
            autoImport = true
            nav.navigate(Screen.IMPORT)
        }
    }

    // Reopen the last imported archive on launch instead of starting over. The indexed
    // result is loaded from a snapshot (fast); only when that is missing do we rebuild
    // from the saved folder and write a fresh snapshot. While this happens we show a
    // dedicated "opening" state, so it never looks like the import screen again. If the
    // location is gone or unreadable (e.g. an in-place import whose all-files access was
    // revoked) we forget it and fall back to the Import screen.
    LaunchedEffect(Unit) {
        // If onCreate already loaded the snapshot synchronously, we are already on the
        // list; just offer the perspective chooser and skip the splash entirely.
        if (indexState.value == null) {
            val snapshot = File(context.filesDir, "imports/index.snapshot")
            val savedRoots = prefs.importedRoots.ifEmpty { prefs.savedExportPaths }
            if (snapshot.exists() || savedRoots.isNotEmpty()) {
                restoring = true
                val restored = withContext(Dispatchers.IO) {
                    // Fast path: reopen the saved index without reparsing the export.
                    // Only if that is missing or unusable do we rebuild from the saved
                    // folder, then write a fresh snapshot for next time.
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    val fromSnapshot = ArchiveIndex.loadSnapshot(snapshot)
                    if (fromSnapshot != null && fromSnapshot.threads.isNotEmpty()) {
                        fromSnapshot
                    } else if (savedRoots.isNotEmpty()) {
                        // Reopen every root the import covered, not just the first.
                        val rebuilt = runCatching {
                            ArchiveIndex.indexAll(savedRoots.map { File(it) }, prefs.mediaRoots)
                        }.getOrNull()
                        if (rebuilt != null && rebuilt.threads.isNotEmpty()) {
                            val ts = android.os.SystemClock.elapsedRealtime()
                            runCatching { rebuilt.saveSnapshot(snapshot) }
                            rebuilt
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }
                restoring = false
                if (restored != null) {
                    importStatus.value = null
                    indexState.value = restored
                    nav.resetTo(Screen.LIST)
                    // Backfill head snapshots for archives imported by an older build, so
                    // the fast chat-open path works without asking anyone to re-import.
                    withContext(Dispatchers.IO) {
                        runCatching { restored.backfillHeads(restored.threads) }
                    }
                    nav.resetTo(Screen.LIST)
                } else {
                    prefs.savedExportPaths = emptyList()
                    prefs.savedExportPath = null
                    prefs.importedRoots = emptyList()
                    runCatching { snapshot.delete() }
                    importStatus.value = "Couldn't reopen your last import — please import it again."
                }
            }
        }
        maybeAskGlobal()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(com.igviewer.archive.ui.theme.Tokens.Bg)
            .statusBarsPadding(),
    ) {
        // Import progress as a hairline rail along the very top edge, with the phase
        // caption beside it. It sits over the content rather than pushing it around, so
        // scrolling and reading a chat are unaffected while a big import runs.
        importProgress.value?.let { p ->
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Tokens.Bg.copy(alpha = 0.94f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val f = p.fraction
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Tokens.Line),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(f ?: 0.02f)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Tokens.Accent),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = buildString {
                        append(
                            when (p.phase) {
                                ImportProgress.Phase.COPYING -> "Copying"
                                ImportProgress.Phase.EXTRACTING -> "Extracting"
                                ImportProgress.Phase.INDEXING -> "Indexing"
                                else -> "Working"
                            },
                        )
                        if (p.totalItems > 0) append(" ${p.doneItems}/${p.totalItems} chats")
                        if (p.totalBytes > 0) {
                            append(" ${formatBytes(p.doneBytes)}/${formatBytes(p.totalBytes)}")
                        }
                    },
                    color = Tokens.Muted,
                    fontSize = 11.sp,
                )
            }
        }

        AnimatedContent(
            targetState = nav.current,
            transitionSpec = veil(motion.screen),
            label = "screen",
        ) { screen ->
            when (screen) {
            Screen.IMPORT -> {
                // Settings hands off here; start the import as soon as we are on screen,
                // so the progress bar and status banner below are actually visible.
                LaunchedEffect(screen) {
                    if (autoImport && pickedName != null && !indexing) {
                        autoImport = false
                        pickAndIndex()
                    }
                }
                ImportScreen(
                pickedName = pickedName,
                indexing = indexing,
                progress = importProgress.value,
                status = importStatus.value,
                onPickZip = { launchZipPicker { name -> pickedName = name } },
                onPickDir = { launchDirPicker { name -> pickedName = name } },
                onIndex = { pickAndIndex() },
                downloadFolderName = prefs.downloadTreeName,
                onChooseDownloadFolder = { askDownloadFolder() },
                )
            }
            Screen.LIST -> ChatListScreen(
                chats = chats,
                onChatClick = { key ->
                    sfx.tap()
                    selectedKey = key
                    targetMsgIndex = null
                    nav.navigate(Screen.CHAT)
                },
                onSearchClick = { sfx.tap(); nav.navigate(Screen.SEARCH) },
                onSettingsClick = { sfx.tap(); nav.navigate(Screen.SETTINGS) },
            )
            Screen.CHAT -> {
                val key = selectedKey
                ChatScreen(
                    // Same title the chat list shows: renamed, and without you in it.
                    chatName = key?.let { k -> chats.firstOrNull { it.key == k }?.name } ?: "",
                    messages = chatMessages,
                    onBack = { sfx.tap(); nav.back() },
                    scrollToIndex = targetMsgIndex,
                    selfName = chatSelf,
                    onChooseSelf = { sfx.tap(); key?.let { threadChooserKey = it } },
                    autoplayVideos = settings.autoplayVideos,
                    chatKey = key.orEmpty(),
                    archiveKey = index?.root?.name.orEmpty(),
                    onNeedDownloadFolder = askDownloadFolder,
                    onRenamed = { renamesVersion++ },
                    onChooseSelfFor = { raw ->
                        val chatKey = key ?: return@ChatScreen
                        perThreadMe[chatKey] = raw
                        prefs.setThreadMe(chatKey, raw)
                    },
                    avatarVersion = avatarVersion,
                    onPickAvatar = pickAvatarLauncher,
                )
            }
            Screen.SEARCH -> {
                val noteResults = remember(index, notesVersion) {
                    val dateFmt = java.text.SimpleDateFormat("EEE d MMM yyyy", java.util.Locale.getDefault())
                    val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    val archiveKey = index?.root?.name.orEmpty()
                    val byThread = NoteStore.all(prefs)
                        .filter { it.archiveKey == archiveKey }
                    byThread.mapNotNull { n ->
                        val msgs = index?.messages(n.threadKey).orEmpty()
                        val msg = msgs.getOrNull(n.msgIndex) ?: return@mapNotNull null
                        val chatName = index?.thread(n.threadKey)?.title?.ifBlank { n.threadKey }.orEmpty()
                        NoteResult(
                            note = n,
                            chatName = chatName,
                            date = dateFmt.format(java.util.Date(msg.tsMs)),
                            time = timeFmt.format(java.util.Date(msg.tsMs)),
                        )
                    }
                }
                SearchScreen(
                    search = { q -> index?.let { UiMapping.search(it, q) } ?: emptyList() },
                    onBack = { sfx.tap(); nav.back() },
                    onResultClick = { key, msgIndex ->
                        sfx.tap()
                        selectedKey = key
                        targetMsgIndex = msgIndex
                        nav.navigate(Screen.CHAT)
                    },
                    notes = noteResults,
                    notesVersion = notesVersion,
                    onNoteClick = { nr ->
                        sfx.tap()
                        selectedKey = nr.note.threadKey
                        targetMsgIndex = nr.note.msgIndex
                        nav.navigate(Screen.CHAT)
                    },
                    onNoteDelete = { nr ->
                        NoteStore.delete(prefs, nr.note.archiveKey, nr.note.threadKey, nr.note.msgIndex)
                        notesVersion++
                    },
                )
            }
            // These run the same import the home screen does, then land on the chat list.
            // Previously they only stashed the picked name, which the Settings screen has
            // nowhere to show, so tapping them looked like a dead button.
            Screen.SETTINGS -> SettingsScreen(
                onBack = { sfx.tap(); nav.back() },
                onPickZip = { importFromSettings { cb -> launchZipPicker(cb) } },
                onPickDir = { importFromSettings { cb -> launchDirPicker(cb) } },
                onDeleteIndex = {
                    selectedKey = null
                    index?.root?.name?.let { NoteStore.forgetArchive(prefs, it) }
                    notesVersion++
                    onDeleteIndex()
                    nav.resetTo(Screen.IMPORT)
                },
                onGrantAccess = onGrantAccess,
                meName = selfGlobal,
                onChooseMe = { globalChooser = true },
                settings = settings,
                downloadFolderName = prefs.downloadTreeName,
                onChooseDownloadFolder = { sfx.tap(); askDownloadFolder() },
                mediaRoots = prefs.mediaRoots.map { it.absolutePath },
                onAddMediaFolder = { askMediaFolder() },
                onRemoveMediaFolder = removeMediaFolder,
            )
            }
        }

        if (globalChooser) {
            WhoAmIDialog(
                title = "Who are you?",
                candidates = index?.allParticipantNames() ?: emptyList(),
                current = selfGlobal,
                allowClear = false,
                onPick = { name ->
                    selfGlobal = name
                    prefs.globalMe = name
                    globalChooser = false
                },
                onClear = {},
                onDismiss = { globalChooser = false },
            )
        }
        threadChooserKey?.let { key ->
            WhoAmIDialog(
                title = "Who are you in this chat?",
                candidates = index?.thread(key)?.participants ?: emptyList(),
                current = effectiveMe(key),
                allowClear = hasOverride(key),
                onPick = { name ->
                    perThreadMe[key] = name
                    prefs.setThreadMe(key, name)
                    threadChooserKey = null
                },
                onClear = {
                    perThreadMe.remove(key)
                    prefs.clearThreadMe(key)
                    threadChooserKey = null
                },
                onDismiss = { threadChooserKey = null },
            )
        }

        if (lightboxVisible) {
            Lightbox(onClose = { lightboxVisible = false })
        }

        if (askAllFiles) {
            AlertDialog(
                onDismissRequest = {
                    askAllFiles = false
                    prefs.allFilesPromptDismissed = true
                },
                title = { Text("Faster imports?") },
                text = {
                    Text(
                        "All-files access is not required, but granting it lets the app read " +
                            "your Instagram data in place and import faster, without copying " +
                            "files. You can change this anytime in Settings.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        sfx.tap()
                        askAllFiles = false
                        prefs.allFilesPromptDismissed = true
                        onGrantAccess()
                    }) { Text("Grant access") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        sfx.tap()
                        askAllFiles = false
                        prefs.allFilesPromptDismissed = true
                    }) { Text("Not now") }
                },
            )
        }

        // Covers the underlying screens while the saved index is being reopened, so a
        // relaunch shows a brief "opening" state rather than the import screen again.
        if (restoring) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(com.igviewer.archive.ui.theme.Tokens.Bg),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Opening your archive…",
                    color = com.igviewer.archive.ui.theme.Tokens.Muted,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun Lightbox(onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Full-size image",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", fontSize = 20.sp)
        }
    }
}
