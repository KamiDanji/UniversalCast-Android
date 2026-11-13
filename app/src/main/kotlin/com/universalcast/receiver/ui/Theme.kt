package com.universalcast.receiver.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    val Focus = Color(0xFFFFFFFF)
}

private val scheme = darkColorScheme(
    primary = Palette.Accent, onPrimary = Palette.OnAccent,
    background = Palette.Background, onBackground = Palette.Text,
    surface = Palette.Surface, onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh, onSurfaceVariant = Palette.TextDim,
    error = Palette.Danger,
)

// TV text is viewed from ~3 m: larger than phone defaults.
private val typography = Typography(
    displayLarge = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 20.sp),
    bodyMedium = TextStyle(fontSize = 18.sp),
    labelLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun UniversalCastTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}

/** D-pad focus: a white ring that fades in, plus a springy 4% zoom so the focused item clearly "lifts". */
@Composable
fun Modifier.focusRing(radius: Int = 14): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (focused) 1.04f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 400f), label = "focusScale",
    )
    val ring by androidx.compose.animation.core.animateFloatAsState(if (focused) 1f else 0f, androidx.compose.animation.core.tween(160), label = "focusRing")
    return this
        .onFocusChanged { focused = it.isFocused }
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .border(3.dp, Palette.Focus.copy(alpha = ring), RoundedCornerShape(radius.dp))
}

@Composable
fun Modifier.focusableRing(): Modifier = this.focusable().focusRing()
