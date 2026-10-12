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
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.igviewer.archive.data.ArchiveIndex
import com.igviewer.archive.data.ArchivePicker
import com.igviewer.archive.data.UiMapping
import com.igviewer.archive.ui.NavState
import com.igviewer.archive.ui.Screen
import com.igviewer.archive.ui.screens.ChatListScreen
import com.igviewer.archive.ui.screens.ChatScreen
import com.igviewer.archive.ui.screens.ImportScreen
import com.igviewer.archive.ui.screens.MessageItem
import com.igviewer.archive.ui.screens.SearchScreen
import com.igviewer.archive.ui.screens.SettingsScreen
import com.igviewer.archive.ui.theme.IGArchiveTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var picker: ArchivePicker
    private var pendingUri: Uri? = null
    private var pendingIsFolder: Boolean = false

    private var onRenamed: ((String) -> Unit)? = null

    private val indexState: MutableState<ArchiveIndex?> = mutableStateOf(null)
    private val importStatus: MutableState<String?> = mutableStateOf(null)

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
        lifecycleScope.launch {
            runCatching {
                val path = withContext(Dispatchers.IO) {
                    if (isFolder) {
                        // Read a local folder in place only when we actually hold
                        // broad storage access; otherwise copy via SAF.
                        val direct = if (hasAllFilesAccess()) picker.directPath(uri) else null
                        if (direct != null) direct.absolutePath
                        else picker.copyToAppStorage(uri, true, "export")
                    } else {
                        picker.extractZip(uri, picker.exportDir())
                    }
                }
                withContext(Dispatchers.IO) {
                    ArchiveIndex.index(File(path))
                }
            }.onSuccess { index ->
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
                onDone(true)
            }.onFailure { e ->
                importStatus.value = "Import failed: ${e.message}"
                onDone(false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        picker = ArchivePicker(this)
        setContent {
            IGArchiveTheme {
                AppRoot(
                    indexState = indexState,
                    importStatus = importStatus,
                    launchZipPicker = { cb -> onRenamed = cb; pickZip.launch(arrayOf("application/zip")) },
                    launchDirPicker = { cb -> onRenamed = cb; pickDir.launch(null) },
                    runImport = ::runImport,
                    onGrantAccess = ::requestAllFilesAccess,
                    onDeleteIndex = ::deleteIndex,
                )
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
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                File(filesDir, "imports").deleteRecursively()
            }
        }
    }
}

@Composable
private fun AppRoot(
    indexState: MutableState<ArchiveIndex?>,
    importStatus: MutableState<String?>,
    launchZipPicker: ((String) -> Unit) -> Unit,
    launchDirPicker: ((String) -> Unit) -> Unit,
    runImport: ((Boolean) -> Unit) -> Unit,
    onGrantAccess: () -> Unit,
    onDeleteIndex: () -> Unit,
) {
    val nav = remember { NavState() }
    val index = indexState.value
    var lightboxVisible by remember { mutableStateOf(false) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var indexing by remember { mutableStateOf(false) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var chatMessages by remember { mutableStateOf<List<MessageItem>>(emptyList()) }

    LaunchedEffect(index, selectedKey) {
        val key = selectedKey
        chatMessages = if (index != null && key != null) {
            withContext(Dispatchers.Default) { UiMapping.messageItems(index, key) }
        } else {
            emptyList()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(com.igviewer.archive.ui.theme.Tokens.Bg)
            .statusBarsPadding(),
    ) {
        when (nav.current) {
            Screen.IMPORT -> ImportScreen(
                pickedName = pickedName,
                indexing = indexing,
                status = importStatus.value,
                onPickZip = { launchZipPicker { name -> pickedName = name } },
                onPickDir = { launchDirPicker { name -> pickedName = name } },
                onIndex = {
                    indexing = true
                    runImport { ok ->
                        indexing = false
                        if (ok) nav.resetTo(Screen.LIST)
                    }
                },
            )
            Screen.LIST -> ChatListScreen(
                chats = index?.let { UiMapping.chatItems(it) } ?: emptyList(),
                onChatClick = { key ->
                    selectedKey = key
                    nav.navigate(Screen.CHAT)
                },
                onSearchClick = { nav.navigate(Screen.SEARCH) },
                onSettingsClick = { nav.navigate(Screen.SETTINGS) },
            )
            Screen.CHAT -> {
                val key = selectedKey
                ChatScreen(
                    chatName = key?.let { index?.thread(it)?.title?.ifBlank { it } } ?: "",
                    messages = chatMessages,
                    onBack = { nav.back() },
                    onSearchClick = { nav.navigate(Screen.SEARCH) },
                )
            }
            Screen.SEARCH -> SearchScreen(
                search = { q -> index?.let { UiMapping.search(it, q) } ?: emptyList() },
                onBack = { nav.back() },
                onResultClick = { key ->
                    selectedKey = key
                    nav.resetTo(Screen.CHAT)
                },
            )
            Screen.SETTINGS -> SettingsScreen(
                onBack = { nav.back() },
                onPickZip = { launchZipPicker { name -> pickedName = name } },
                onPickDir = { launchDirPicker { name -> pickedName = name } },
                onDeleteIndex = {
                    selectedKey = null
                    onDeleteIndex()
                    nav.resetTo(Screen.IMPORT)
                },
                onGrantAccess = onGrantAccess,
            )
        }

        if (lightboxVisible) {
            Lightbox(onClose = { lightboxVisible = false })
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
