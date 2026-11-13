package com.universalcast.receiver.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val container = when {
        danger -> Palette.Danger
        primary -> Palette.Accent
        else -> Palette.SurfaceHigh
    }
    val content = if (primary || danger) Palette.OnAccent else Palette.Text
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content,
            disabledContainerColor = Palette.SurfaceHigh, disabledContentColor = Palette.TextDim),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
        modifier = modifier.focusRing(),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.reveal().animateContentSize()
            .background(Brush.linearGradient(listOf(Palette.SurfaceHigh, Palette.Surface)), RoundedCornerShape(20.dp))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(20.dp)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun Chip(text: String, color: Color, live: Boolean = false) {
    Row(
        Modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (live) PulseDot(color, 10.dp)
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ScreenScaffold(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().appBackground().padding(horizontal = 56.dp, vertical = 36.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (onBack != null) TvButton("‹ Back", onBack, primary = false)
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Palette.Text, modifier = Modifier.weight(1f))
            actions()
        }
        Spacer(Modifier.height(24.dp))
        content()
    }
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatDate(ms: Long?): String =
    if (ms == null) "never" else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
