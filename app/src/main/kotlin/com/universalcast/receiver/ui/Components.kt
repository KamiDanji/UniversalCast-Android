package com.universalcast.receiver.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.OutlinedButtonDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text

/**
 * The one button of the TV UI, built on TV Material: [primary] = filled accent, otherwise outlined; [danger] = error tone.
 * TV Material provides the D-pad focus treatment (scale, colour inversion) itself.
 */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val padding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)
    if (primary || danger) {
        Button(
            onClick = onClick, enabled = enabled, modifier = modifier, contentPadding = padding,
            shape = ButtonDefaults.shape(RoundedCornerShape(14.dp)),
            colors = ButtonDefaults.colors(
                containerColor = if (danger) cs.errorContainer else cs.primary,
                contentColor = if (danger) cs.onErrorContainer else cs.onPrimary,
                focusedContainerColor = if (danger) cs.error else cs.onSurface,
                focusedContentColor = if (danger) cs.onError else cs.surface,
                disabledContainerColor = cs.surfaceVariant.copy(alpha = 0.5f),
                disabledContentColor = cs.onSurfaceVariant.copy(alpha = 0.6f),
            ),
        ) { Text(text, style = MaterialTheme.typography.labelLarge) }
    } else {
        OutlinedButton(
            onClick = onClick, enabled = enabled, modifier = modifier, contentPadding = padding,
            shape = ButtonDefaults.shape(RoundedCornerShape(14.dp)),
            colors = OutlinedButtonDefaults.colors(
                containerColor = Color.Transparent, contentColor = cs.onSurface,
                focusedContainerColor = cs.onSurface, focusedContentColor = cs.surface,
                disabledContentColor = cs.onSurfaceVariant.copy(alpha = 0.6f),
            ),
        ) { Text(text, style = MaterialTheme.typography.labelLarge) }
    }
}

/** A tonal content group (TV Material [Surface] with the surface-variant container). */
@Composable
fun InfoCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.reveal().animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** Tonal status badge, optionally with a pulsing dot for something live. */
@Composable
fun Chip(text: String, color: Color, live: Boolean = false) {
    Row(
        Modifier.background(color.copy(alpha = 0.16f), CircleShape).padding(horizontal = 14.dp, vertical = 5.dp),
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
            Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            actions()
        }
        Spacer(Modifier.height(24.dp))
        content()
    }
}

/**
 * TV dialog: a centred TV Material surface over a scrim. The first button gets focus automatically so a single
 * OK press confirms the default action.
 */
@Composable
fun TvDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
        ) {
            Column(Modifier.padding(32.dp).widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                content()
                // Focus lands on the first (safe/default) button so one OK press answers the dialog.
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                Row(Modifier.focusRequester(first).focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp), content = buttons)
            }
        }
    }
}

/** Requests focus on the composable once it enters the composition (one OK press for the default action). */
@Composable
fun Modifier.autoFocus(): Modifier {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    return this.focusRequester(fr)
}

/** Single-line text input with a TV-style focus border (TV Material has no text field); opens the TV keyboard on OK. */
@Composable
fun TvTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = cs.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(cs.primary),
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            .background(cs.background, RoundedCornerShape(14.dp))
            .border(if (focused) 3.dp else 1.dp, if (focused) cs.primary else cs.border, RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
    )
}

/** Determinate progress bar (TV Material has none). */
@Composable
fun TvProgressBar(progress: Float, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 8.dp) {
    Box(modifier.height(height).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f), CircleShape)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(MaterialTheme.colorScheme.primary, CircleShape))
    }
}

/** Indeterminate spinner (TV Material has none). */
@Composable
fun TvSpinner(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(
        0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1000, easing = androidx.compose.animation.core.LinearEasing)),
        label = "angle",
    )
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = 4.dp.toPx()
        drawArc(
            color, startAngle = angle, sweepAngle = 270f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
            size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatDate(ms: Long?): String =
    if (ms == null) "never" else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
