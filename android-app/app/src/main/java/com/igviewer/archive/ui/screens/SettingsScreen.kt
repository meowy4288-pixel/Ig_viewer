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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igviewer.archive.ui.components.AppBar
import com.igviewer.archive.ui.components.Hairline
import com.igviewer.archive.ui.theme.AppSettings
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
) {
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
                HairRow()
                SwitchRow(
                    label = "Animations",
                    description = "Transitions and fades",
                    checked = settings.animations,
                    onChange = { settings.updateAnimations(it) },
                )
            }

            Section("Media") {
                SwitchRow(
                    label = "Autoplay videos",
                    description = "Start playing as soon as a video opens",
                    checked = settings.autoplayVideos,
                    onChange = { settings.updateAutoplayVideos(it) },
                )
            }

            Section("Sound") {
                SwitchRow(
                    label = "Sound effects",
                    description = "A soft tick on taps",
                    checked = settings.sfxEnabled,
                    onChange = { settings.updateSfxEnabled(it) },
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
                OutlineButton("Choose .zip export…", onPickZip)
                Spacer(Modifier.height(8.dp))
                OutlineButton("Choose export folder…", onPickDir)
                Spacer(Modifier.height(8.dp))
                OutlineButton("Grant all-files access (faster, no copy)", onGrantAccess)
                Spacer(Modifier.height(8.dp))
                OutlineButton("Delete imported data", onDeleteIndex, danger = true)
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "This app is fully offline. Nothing here is uploaded, and your export " +
                    "folder is never modified.",
                color = Tokens.Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 32.dp),
            )
        }
    }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ThemeVariant.entries.forEach { variant ->
            val active = variant == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .border(
                        width = if (active) 2.dp else 1.dp,
                        color = if (active) Tokens.Accent else Tokens.Line,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .background(variant.palette.bg)
                    .clickable { onSelect(variant) }
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
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
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
