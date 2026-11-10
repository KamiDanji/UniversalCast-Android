package com.universalcast.sender.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object Palette {
    val Background = Color(0xFF0B0F17)
    val Surface = Color(0xFF141B27)
    val SurfaceHigh = Color(0xFF1C2535)
    val Accent = Color(0xFF5B8CFF)
    val OnAccent = Color(0xFF06122E)
    val Text = Color(0xFFE8ECF4)
    val TextDim = Color(0xFF9AA6BC)
    val Ok = Color(0xFF4CD08A)
    val Warn = Color(0xFFFFC857)
    val Danger = Color(0xFFFF6B6B)
}

private val scheme = darkColorScheme(
    primary = Palette.Accent, onPrimary = Palette.OnAccent,
    background = Palette.Background, onBackground = Palette.Text,
    surface = Palette.Surface, onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh, onSurfaceVariant = Palette.TextDim,
    secondaryContainer = Palette.SurfaceHigh, onSecondaryContainer = Palette.Text,
    error = Palette.Danger,
)

@Composable
fun SenderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Palette.Surface, RoundedCornerShape(20.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun Chip(text: String, color: Color) {
    Text(
        text, color = color, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
