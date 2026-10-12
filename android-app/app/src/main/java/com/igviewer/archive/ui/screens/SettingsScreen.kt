package com.igviewer.archive.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igviewer.archive.ui.components.AppBar
import com.igviewer.archive.ui.components.Hairline
import com.igviewer.archive.ui.theme.Tokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onPickZip: () -> Unit,
    onPickDir: () -> Unit,
    onDeleteIndex: () -> Unit,
    onGrantAccess: () -> Unit,
) {
    var theme by remember { mutableStateOf("Dark") }
    var textSize by remember { mutableFloatStateOf(16f) }
    var density by remember { mutableStateOf("Cozy") }
    var animations by remember { mutableStateOf(true) }
    var timezone by remember { mutableStateOf("Asia/Kolkata") }

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
                .padding(horizontal = 16.dp),
        ) {
            SettingRow(label = "Theme") {
                DropdownSetting(theme, listOf("Dark", "Light")) { theme = it }
            }
            SettingRow(label = "Text size") {
                Slider(
                    value = textSize,
                    onValueChange = { textSize = it },
                    valueRange = 13f..20f,
                    modifier = Modifier.width(150.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Tokens.Accent,
                        activeTrackColor = Tokens.Accent,
                        inactiveTrackColor = Tokens.Surface,
                    ),
                )
            }
            SettingRow(label = "Row density") {
                DropdownSetting(density, listOf("Cozy", "Compact")) { density = it }
            }
            SettingRow(label = "Animations") {
                Checkbox(
                    checked = animations,
                    onCheckedChange = { animations = it },
                )
            }
            SettingRow(label = "Default timezone") {
                OutlinedTextField(
                    value = timezone,
                    onValueChange = { timezone = it },
                    singleLine = true,
                    modifier = Modifier.width(150.dp),
                    colors = fieldColors(),
                )
            }

            Spacer(Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton("Choose .zip export…", onPickZip)
                OutlineButton("Choose export folder…", onPickDir)
                OutlineButton("Grant all-files access (faster, no copy)", onGrantAccess)
                OutlineButton("Delete index", onDeleteIndex, danger = true)
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
        Text(label, color = if (danger) Tokens.Text else Tokens.Text)
    }
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = Tokens.Text, fontSize = 16.sp)
            content()
        }
        Hairline()
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Tokens.Text,
    unfocusedTextColor = Tokens.Text,
    focusedBorderColor = Tokens.Line,
    unfocusedBorderColor = Tokens.Line,
    cursorColor = Tokens.Accent,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSetting(
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .width(150.dp),
            colors = fieldColors(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, color = Tokens.Text) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
