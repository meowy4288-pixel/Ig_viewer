package com.igviewer.archive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.igviewer.archive.ui.screens.SearchScreen
import com.igviewer.archive.ui.screens.SettingsScreen
import com.igviewer.archive.ui.theme.AppSettings
import com.igviewer.archive.ui.theme.IGArchiveTheme
import com.igviewer.archive.ui.theme.LocalAnimations
import com.igviewer.archive.ui.theme.LocalCompactRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var picker: ArchivePicker
    private lateinit var prefs: Prefs
    private var pendingUri: Uri? = null
    private var pendingIsFolder: Boolean = false

    private var onRenamed: ((String) -> Unit)? = null

    /** Persisted index snapshot, rebuilt only when missing (see AppRoot's restore). */
    private val snapshotFile: File get() = File(filesDir, "imports/index.snapshot")

    private val indexState: MutableState<ArchiveIndex?> = mutableStateOf(null)
    private val importStatus: MutableState<String?> = mutableStateOf(null)
    private val importProgress: MutableState<ImportProgress?> = mutableStateOf(null)

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            pendingUri = it
            pendingIsFolder = false
            onRenamed?.invoke("export.zip")
        }
    }

    private val pickDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            pendingUri = it
            pendingIsFolder = true
            onRenamed?.invoke("export")
        }
    }

    private fun runImport(onDone: (Boolean) -> Unit) {
        val uri = pendingUri ?: run { onDone(false); return }
        val isFolder = pendingIsFolder
        importStatus.value = null
        importProgress.value = null
        lifecycleScope.launch {
            runCatching {
                val path = withContext(Dispatchers.IO) {
                    if (isFolder) {
                        // Read a local folder in place only when we actually hold
                        // broad storage access; otherwise copy via SAF.
                        val direct = if (hasAllFilesAccess()) picker.directPath(uri) else null
                        if (direct != null) direct.absolutePath
                        else picker.copyToAppStorage(uri, true, "export") { importProgress.value = it }
                    } else {
                        picker.extractZip(uri, picker.exportDir()) { importProgress.value = it }
                    }
                }
                withContext(Dispatchers.IO) {
                    val idx = ArchiveIndex.index(File(path)) { done, total ->
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
                    idx
                } to path
            }.onSuccess { (index, path) ->
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
                        append(". Expected <export>/your_instagram_activity/messages/inbox/<thread>/message_1.json.")
                    }
                    onDone(false)
                    return@onSuccess
                }
                val msg = buildString {
                    append("Imported ${index.threads.size} chats")
                    if (skipped > 0) append(" · $skipped files skipped")
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                // Remember where the archive lives so the next launch reopens it.
                prefs.savedExportPath = path
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
                ?.let { indexState.value = it }
        }

        val settings = AppSettings(prefs)
        setContent {
            IGArchiveTheme(variant = settings.theme) {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalCompactRows provides settings.compactRows,
                    LocalAnimations provides settings.animations,
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
                    )
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
        prefs.savedExportPath = null
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
) {
    val nav = remember { NavState(if (indexState.value != null) Screen.LIST else Screen.IMPORT) }
    val index = indexState.value
    val chats = remember(index) { index?.let { UiMapping.chatItems(it) } ?: emptyList() }
    val context = LocalContext.current
    val sfx = remember { Sfx(context) }
    DisposableEffect(sfx) { onDispose { sfx.release() } }
    SideEffect { sfx.enabled = settings.sfxEnabled }
    val animations = LocalAnimations.current

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

    // System/gesture back mirrors the in-app back arrow. When there is nowhere to go
    // (import screen, or the chat list root) the handler stays disabled, so the system
    // default of finishing the activity applies.
    BackHandler(enabled = nav.canGoBack) { nav.back() }

    LaunchedEffect(index, selectedKey, chatSelf) {
        val key = selectedKey
        chatMessages = if (index != null && key != null) {
            withContext(Dispatchers.Default) { UiMapping.messageItems(index, key, chatSelf) }
        } else {
            emptyList()
        }
    }

    // After a fresh import, offer the global chooser once when the perspective is
    // genuinely ambiguous (more than one candidate and no saved choice).
    fun maybeAskGlobal() {
        val idx = indexState.value ?: return
        if (selfGlobal == null && idx.allParticipantNames().size >= 2) globalChooser = true
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
            val savedPath = prefs.savedExportPath
            if (snapshot.exists() || savedPath != null) {
                restoring = true
                val restored = withContext(Dispatchers.IO) {
                    // Fast path: reopen the saved index without reparsing the export.
                    // Only if that is missing or unusable do we rebuild from the saved
                    // folder, then write a fresh snapshot for next time.
                    val fromSnapshot = ArchiveIndex.loadSnapshot(snapshot)
                    if (fromSnapshot != null && fromSnapshot.threads.isNotEmpty()) {
                        fromSnapshot
                    } else if (savedPath != null) {
                        val rebuilt = runCatching { ArchiveIndex.index(File(savedPath)) }.getOrNull()
                        if (rebuilt != null && rebuilt.threads.isNotEmpty()) {
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
                } else {
                    prefs.savedExportPath = null
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
        AnimatedContent(
            targetState = nav.current,
            transitionSpec = {
                val d = if (animations) 180 else 0
                (fadeIn(tween(d)) + slideInHorizontally { it / 14 }) togetherWith fadeOut(tween(if (animations) 120 else 0))
            },
            label = "screen",
        ) { screen ->
            when (screen) {
            Screen.IMPORT -> ImportScreen(
                pickedName = pickedName,
                indexing = indexing,
                progress = importProgress.value,
                status = importStatus.value,
                onPickZip = { launchZipPicker { name -> pickedName = name } },
                onPickDir = { launchDirPicker { name -> pickedName = name } },
                onIndex = {
                    indexing = true
                    runImport { ok ->
                        indexing = false
                        if (ok) {
                            nav.resetTo(Screen.LIST)
                            maybeAskGlobal()
                        }
                    }
                },
            )
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
                    chatName = key?.let { index?.thread(it)?.title?.ifBlank { it } } ?: "",
                    messages = chatMessages,
                    onBack = { sfx.tap(); nav.back() },
                    scrollToIndex = targetMsgIndex,
                    selfName = chatSelf,
                    onChooseSelf = { sfx.tap(); key?.let { threadChooserKey = it } },
                    autoplayVideos = settings.autoplayVideos,
                    chatKey = key.orEmpty(),
                )
            }
            Screen.SEARCH -> SearchScreen(
                search = { q -> index?.let { UiMapping.search(it, q) } ?: emptyList() },
                onBack = { sfx.tap(); nav.back() },
                onResultClick = { key, msgIndex ->
                    sfx.tap()
                    selectedKey = key
                    targetMsgIndex = msgIndex
                    nav.navigate(Screen.CHAT)
                },
            )
            Screen.SETTINGS -> SettingsScreen(
                onBack = { sfx.tap(); nav.back() },
                onPickZip = { launchZipPicker { name -> pickedName = name } },
                onPickDir = { launchDirPicker { name -> pickedName = name } },
                onDeleteIndex = {
                    selectedKey = null
                    onDeleteIndex()
                    nav.resetTo(Screen.IMPORT)
                },
                onGrantAccess = onGrantAccess,
                meName = selfGlobal,
                onChooseMe = { globalChooser = true },
                settings = settings,
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
                            "your export in place and import faster, without copying files. " +
                            "You can change this anytime in Settings.",
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
