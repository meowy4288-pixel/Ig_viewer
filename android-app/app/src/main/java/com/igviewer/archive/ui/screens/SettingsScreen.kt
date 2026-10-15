package com.igviewer.archive.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igviewer.archive.ui.components.AppBar
import com.igviewer.archive.ui.components.Hairline
import com.igviewer.archive.ui.theme.AppSettings
import com.igviewer.archive.ui.theme.MOTION_ENABLED
import com.igviewer.archive.ui.theme.ThemeVariant
import com.igviewer.archive.ui.theme.Tokens

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onPickZip: () -> Unit,
    onPickDir: () -> Unit,
    onDeleteIndex: () -> Unit,
    onGrantAccess: () -> Unit,
    meName: String? = null,
    onChooseMe: () -> Unit = {},
    settings: AppSettings,
    /** Folder "Download" writes into, or null when none has been chosen yet. */
    downloadFolderName: String? = null,
    /** Opens the system folder picker to set the download destination. */
    onChooseDownloadFolder: () -> Unit = {},
    /** Extra folders holding media referenced by the chats, outside any import root. */
    mediaRoots: List<String> = emptyList(),
    /** Opens the folder picker to add one. */
    onAddMediaFolder: () -> Unit = {},
    /** Removes one and re-indexes. */
    onRemoveMediaFolder: (String) -> Unit = {},
) {
    // Deleting wipes every imported export on this phone, so it always asks first.
    var confirmDelete by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        AppBar {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Tokens.Text)
                }
                Text(
                    text = "Settings",
                    color = Tokens.Gilt,
                    fontFamily = Tokens.Serif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(16.dp))

            Section("Customization") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Theme", color = Tokens.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                }
                ThemePicker(
                    selected = settings.theme,
                    onSelect = { settings.theme = it },
                )
                HairRow()
                TextRow("Text size", "Shown live across the app", Modifier.padding(top = 14.dp))
                Slider(
                    value = settings.textScale,
                    onValueChange = { settings.updateTextScale(it) },
                    valueRange = 0.85f..1.3f,
                    colors = sliderColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                HairRow()
                SegmentedRow(
                    label = "Row density",
                    options = listOf("Cozy", "Compact"),
                    selectedIndex = if (settings.compactRows) 1 else 0,
                    onSelect = { settings.updateCompactRows(it == 1) },
                )
            }

            Section("Media") {
                SwitchRow(
                    label = "Autoplay videos",
                    description = "Start playing as soon as a video opens",
                    checked = settings.autoplayVideos,
                    onChange = { settings.updateAutoplayVideos(it) },
                )
                HairRow()
                OutlinedButton(
                    onClick = onChooseDownloadFolder,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                ) {
                    Text(
                        text = if (downloadFolderName.isNullOrBlank()) {
                            "Choose download folder"
                        } else {
                            "Download folder · $downloadFolderName"
                        },
                        color = Tokens.Text,
                        fontSize = 13.sp,
                    )
                }
                Text(
                    text = "Long-press a photo, video or voice note to download or copy it.",
                    color = Tokens.Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                HairRow()
                Text(
                    text = "Extra media folders",
                    color = Tokens.Text,
                    fontSize = 15.sp,
                )
                Text(
                    text = "Some exports keep photos, videos and voice notes in a different " +
                        "folder from the chats. Add that folder here and its media shows up " +
                        "in the right chats.",
                    color = Tokens.Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                )
                mediaRoots.forEach { path ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = path.substringAfterLast('/'),
                            color = Tokens.Text,
                            fontSize = 13.sp,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onRemoveMediaFolder(path) }) {
                            Text("Remove", color = Tokens.Muted, fontSize = 12.sp)
                        }
                    }
                }
                OutlinedButton(
                    onClick = onAddMediaFolder,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    Text("Add media folder", color = Tokens.Text, fontSize = 13.sp)
                }
                Spacer(Modifier.height(10.dp))
            }

            Section("Sound") {
                SwitchRow(
                    label = "Sound effects",
                    description = "A soft tick on taps",
                    checked = settings.sfxEnabled,
                    onChange = { settings.updateSfxEnabled(it) },
                )
            }

            // Hidden while the motion system is switched off (see Motion.MOTION_ENABLED),
            // so the toggles never promise an effect that cannot happen.
            if (MOTION_ENABLED) ExpandableSection("Motion") {
                SwitchRow(
                    label = "All animations",
                    description = "Master switch for everything below",
                    checked = settings.animations,
                    onChange = { settings.updateAnimations(it) },
                )
                HairRow()
                SwitchRow(
                    label = "Screen transitions",
                    description = "Fade-and-rise between screens",
                    checked = settings.animScreen,
                    onChange = { settings.updateAnimScreen(it) },
                )
                HairRow()
                SwitchRow(
                    label = "Theme crossfade",
                    description = "Colors fade when switching theme",
                    checked = settings.animThemeFade,
                    onChange = { settings.updateAnimThemeFade(it) },
                )
                HairRow()
                SwitchRow(
                    label = "Press feedback",
                    description = "Rows and chips sink on tap",
                    checked = settings.animPress,
                    onChange = { settings.updateAnimPress(it) },
                )
                HairRow()
                SwitchRow(
                    label = "List entrance",
                    description = "Chats and tiles drift in",
                    checked = settings.animListRise,
                    onChange = { settings.updateAnimListRise(it) },
                )
                HairRow()
                SwitchRow(
                    label = "Message cascade",
                    description = "Bubbles settle in when a chat opens",
                    checked = settings.animCascade,
                    onChange = { settings.updateAnimCascade(it) },
                )
                HairRow()
                SwitchRow(
                    label = "Date label slide",
                    description = "The day label slides when it changes",
                    checked = settings.animDateTick,
                    onChange = { settings.updateAnimDateTick(it) },
                )
                HairRow()
                SwitchRow(
                    label = "Highlight flare",
                    description = "A jumped-to message glows",
                    checked = settings.animFlare,
                    onChange = { settings.updateAnimFlare(it) },
                )
                HairRow()
                SwitchRow(
                    label = "Jump buttons",
                    description = "Jump to top/bottom buttons pop in",
                    checked = settings.animJump,
                    onChange = { settings.updateAnimJump(it) },
                )
            }

            Section("Chat") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("You are", color = Tokens.Text, fontSize = 16.sp)
                        Text(
                            text = meName ?: "Auto-detect",
                            color = if (meName != null) Tokens.Muted else Tokens.Muted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    OutlinedButton(
                        onClick = onChooseMe,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Tokens.Line),
                    ) {
                        Text("Choose", color = Tokens.Text)
                    }
                }
            }

            Section("Data & import") {
                Spacer(Modifier.height(6.dp))
                OutlineButton("Choose .zip files…", onPickZip)
                Spacer(Modifier.height(8.dp))
                OutlineButton("Choose unzipped folder…", onPickDir)
                Spacer(Modifier.height(8.dp))
                OutlineButton("Grant all-files access (faster, no copy)", onGrantAccess)
                Spacer(Modifier.height(8.dp))
                OutlineButton("Delete imported data", { confirmDelete = true }, danger = true)
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "This app is fully offline. Nothing here is uploaded, and the " +
                    "folder you import from is never modified.",
                color = Tokens.Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 32.dp),
            )
        }
    }

    if (confirmDelete) {
        ConfirmDeleteDialog(
            onConfirm = { confirmDelete = false; onDeleteIndex() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun ConfirmDeleteDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.Bg,
        titleContentColor = Tokens.Text,
        textContentColor = Tokens.Muted,
        shape = RoundedCornerShape(18.dp),
        title = { Text("Delete imported data?", fontWeight = FontWeight.SemiBold) },
        text = {
            Text(
                "This removes every export imported into the app from this phone, " +
                    "along with your notes and photos. Your original files are not touched.",
                fontSize = 14.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = Tokens.Gilt, fontSize = 14.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep", color = Tokens.Muted) }
        },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(
        text = title.uppercase(),
        color = Tokens.Muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Tokens.Surface)
            .border(1.dp, Tokens.Line, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp),
    ) {
        content()
    }
}

@Composable
private fun ExpandableSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    val expanded = remember { mutableStateOf(initiallyExpanded) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Tokens.Surface)
                .border(1.dp, Tokens.Line, RoundedCornerShape(14.dp))
                .clickable { expanded.value = !expanded.value }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title.uppercase(),
                color = Tokens.Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                imageVector = if (expanded.value) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = Tokens.Muted,
                modifier = Modifier.size(20.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded.value,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 14.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun HairRow() = Hairline()

@Composable
private fun TextRow(label: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, color = Tokens.Text, fontSize = 16.sp)
        Text(description, color = Tokens.Muted, fontSize = 13.sp)
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Tokens.Text, fontSize = 16.sp)
            Text(description, color = Tokens.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Tokens.OnAccent,
                checkedTrackColor = Tokens.Accent,
                checkedBorderColor = Tokens.Accent,
                uncheckedThumbColor = Tokens.Muted,
                uncheckedTrackColor = Tokens.Surface,
                uncheckedBorderColor = Tokens.Line,
            ),
        )
    }
}

@Composable
private fun SegmentedRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Tokens.Text, fontSize = 16.sp)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, Tokens.Line, RoundedCornerShape(10.dp))
                .background(Tokens.Bg)
                .padding(2.dp),
        ) {
            options.forEachIndexed { i, option ->
                val active = i == selectedIndex
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) Tokens.Accent else Tokens.Bg)
                        .clickable { onSelect(i) }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = option,
                        color = if (active) Tokens.OnAccent else Tokens.Text,
                        fontSize = 14.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemePicker(selected: ThemeVariant, onSelect: (ThemeVariant) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ThemeVariant.entries.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                pair.forEach { variant ->
                    ThemeCard(
                        variant = variant,
                        active = variant == selected,
                        onClick = { onSelect(variant) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ThemeCard(
    variant: ThemeVariant,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (active) 2.dp else 1.dp,
                color = if (active) Tokens.Accent else Tokens.Line,
                shape = RoundedCornerShape(12.dp),
            )
            .background(variant.palette.bg)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(variant.palette.bg)
                    .border(1.dp, variant.palette.gilt, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(variant.palette.accent),
                )
            }
            Box(
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(variant.palette.gilt),
            )
            if (active) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = variant.palette.text,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(16.dp),
                )
            }
        }
        Text(
            text = variant.label,
            color = variant.palette.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun OutlineButton(label: String, onClick: () -> Unit, danger: Boolean = false) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (danger) Tokens.Text else Tokens.Line),
    ) {
        Text(label, color = Tokens.Text)
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Tokens.Accent,
    activeTrackColor = Tokens.Accent,
    inactiveTrackColor = Tokens.Line,
)
