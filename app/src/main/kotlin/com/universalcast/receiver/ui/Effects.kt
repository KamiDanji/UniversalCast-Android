package com.universalcast.receiver.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.asComposePath
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** App backdrop: the TV Material background with one soft tonal glow in the top-right corner. */
@Composable
fun Modifier.appBackground(): Modifier {
    val bg = MaterialTheme.colorScheme.background
    val glow = MaterialTheme.colorScheme.primaryContainer
    return this
        .background(bg)
        .drawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(glow.copy(alpha = 0.40f), Color.Transparent),
                    center = Offset(size.width * 0.92f, 0f), radius = size.maxDimension * 0.6f,
                ),
            )
        }
}

/** Fades and slides a composable in once, [delayMs] after it first appears. */
@Composable
fun Modifier.reveal(delayMs: Int = 0): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(delayMs.toLong()); p.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
    return this.graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 36f }
}

/**
 * Launch animation in the TV Material colours: a tonal disc holding the screen-with-play glyph, cast ripples leaving it,
 * then the wordmark and tagline. Any remote key skips it.
 */
@Composable
fun SplashOverlay(onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val logo = remember { Animatable(0.8f) }
    val title = remember { Animatable(0f) }
    val exit = remember { Animatable(1f) }
    val waves = rememberInfiniteTransition(label = "waves")
    val wave by waves.animateFloat(0f, 1f, infiniteRepeatable(tween(1700, easing = LinearEasing)), label = "wave")
    var finished by remember { mutableStateOf(false) }
    fun finish() { if (!finished) { finished = true; onDone() } }
    val focus = remember { FocusRequester() }
    // Expressive-style shape morph (rounded 12-point burst -> softer 9-point cookie), slowly rotating behind the glyph.
    val morph = remember {
        Morph(
            RoundedPolygon.star(12, innerRadius = 0.72f, rounding = CornerRounding(0.18f), innerRounding = CornerRounding(0.18f)),
            RoundedPolygon.star(9, innerRadius = 0.88f, rounding = CornerRounding(0.4f), innerRounding = CornerRounding(0.4f)),
        )
    }
    val shapeProgress = remember { Animatable(0f) }
    val spin by waves.animateFloat(0f, 360f, infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "spin")
    LaunchedEffect(Unit) {
        launch { shapeProgress.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessVeryLow)) }
        runCatching { focus.requestFocus() }
        launch { logo.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 160f)) }
        delay(200)
        title.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
        delay(900)
        exit.animateTo(0f, tween(350))
        finish()
    }
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = exit.value; scaleX = 1f + (1f - exit.value) * 0.04f; scaleY = 1f + (1f - exit.value) * 0.04f }
            .appBackground()
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { finish(); true },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(340.dp)) {
                val c = center
                val l = logo.value
                for (i in 0..2) {
                    val t = (wave + i / 3f) % 1f
                    drawCircle(
                        cs.primary.copy(alpha = ((1f - t) * 0.30f).coerceIn(0f, 1f)),
                        radius = size.minDimension * (0.30f + 0.20f * t), center = c, style = Stroke(width = 3.dp.toPx()),
                    )
                }
                val box = size.minDimension * 0.66f * l
                val path = morph.toPath(progress = shapeProgress.value).asComposePath()
                val b = path.getBounds()
                val fit = box / maxOf(b.width, b.height, 0.0001f)
                withTransform({
                    translate(c.x, c.y)
                    rotate(spin, pivot = Offset.Zero)
                    scale(fit, fit, pivot = Offset.Zero)
                    translate(-b.center.x, -b.center.y)
                }) { drawPath(path, cs.primaryContainer) }
                val w = size.minDimension * 0.30f * l
                val h = w * 0.68f
                val top = Offset(c.x - w / 2, c.y - h / 2 - 7.dp.toPx() * l)
                drawRoundRect(cs.onPrimaryContainer, top, Size(w, h), CornerRadius(9.dp.toPx() * l))
                val tri = Path().apply {
                    moveTo(c.x - w * 0.11f, top.y + h * 0.28f); lineTo(c.x + w * 0.17f, top.y + h * 0.5f)
                    lineTo(c.x - w * 0.11f, top.y + h * 0.72f); close()
                }
                drawPath(tri, cs.primaryContainer)
                drawRoundRect(
                    cs.onPrimaryContainer, Offset(c.x - w * 0.2f, top.y + h + 9.dp.toPx() * l),
                    Size(w * 0.4f, 5.dp.toPx() * l), CornerRadius(3.dp.toPx()),
                )
            }
            Text(
                "UniversalCast", color = cs.onSurface, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.graphicsLayer { alpha = title.value; translationY = (1f - title.value) * 30f },
            )
            Text(
                "Receiver", color = cs.onSurfaceVariant, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.graphicsLayer { alpha = title.value; translationY = (1f - title.value) * 18f }.padding(top = 4.dp),
            )
        }
    }
}

/** A small dot that gently pulses: marks something live (connected, ready, connecting). */
@Composable
fun PulseDot(color: Color, size: androidx.compose.ui.unit.Dp = 8.dp) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(
        Modifier.size(size)
            .graphicsLayer { alpha = a; scaleX = 0.8f + 0.4f * a; scaleY = 0.8f + 0.4f * a }
            .background(color, CircleShape),
    )
}
