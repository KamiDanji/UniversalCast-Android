package com.universalcast.sender.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Bottom sheet to pick light/dark mode and the colour theme (wallpaper colours on Android 12+, or a seeded palette). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppearanceSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ThemePrefs.get(ctx) }
    val mode by prefs.mode.collectAsState()
    val color by prefs.color.collectAsState()
    val options = remember { ColorTheme.entries.filter { it != ColorTheme.DYNAMIC || Build.VERSION.SDK_INT >= 31 } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Appearance", style = MaterialTheme.typography.titleLarge)

            Text("Mode", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m, onClick = { prefs.setMode(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                    ) { Text(m.label) }
                }
            }

            Text("Colour theme", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                for (t in options) Swatch(t, selected = color == t) { prefs.setColor(t) }
            }
            val vibrant by prefs.vibrant.collectAsState()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Vibrant colours", style = MaterialTheme.typography.titleSmall)
                    Text("Richer, more saturated colours (colour themes only).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = vibrant, onCheckedChange = { prefs.setVibrant(it) })
            }
            if (Build.VERSION.SDK_INT >= 31) {
                Text(
                    "“Wallpaper” follows the colours of your Android wallpaper and changes with it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Swatch(theme: ColorTheme, selected: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val fill: Brush = if (theme == ColorTheme.DYNAMIC && Build.VERSION.SDK_INT >= 31) {
        val s = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        Brush.linearGradient(listOf(s.primary, s.tertiary))
    } else Brush.linearGradient(listOf(theme.swatch, theme.swatch))
    Column(
        Modifier.width(64.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(48.dp).background(fill, CircleShape)
                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) { if (selected) Icon(Icons.Filled.Check, null, tint = Color.White) }
        Text(theme.label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
    }
}
