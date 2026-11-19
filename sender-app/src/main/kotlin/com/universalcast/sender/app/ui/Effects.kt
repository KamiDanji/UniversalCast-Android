package com.universalcast.sender.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.material3.MaterialShapes
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Fades and slides a composable in once, [delayMs] after it first appears. */
@Composable
fun Modifier.reveal(delayMs: Int = 0): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(delayMs.toLong()); p.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
    return this.graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 36f }
}

/**
 * Launch animation, drawn with the live Material 3 colours (so it matches whichever theme/wallpaper colours are active):
 * a tonal disc holding the screen-with-play glyph, cast ripples leaving it, then the wordmark and tagline.
 * Tap anywhere to skip.
 */
@Composable
fun SplashOverlay(onDone: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val logo = remember { Animatable(0.82f) }
    val title = remember { Animatable(0f) }
    val exit = remember { Animatable(1f) }
    val waves = rememberInfiniteTransition(label = "waves")
    val wave by waves.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "wave")
    var finished by remember { mutableStateOf(false) }
    fun finish() { if (!finished) { finished = true; onDone() } }
    val morph = remember { Morph(MaterialShapes.SoftBurst, MaterialShapes.Cookie9Sided) }
    val shapeProgress = remember { Animatable(0f) }
    val spin by waves.animateFloat(0f, 360f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "spin")
    LaunchedEffect(Unit) {
        launch { shapeProgress.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessVeryLow)) }
        launch { logo.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 180f)) }
        delay(180)
        title.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        delay(750)
        exit.animateTo(0f, tween(320))
        finish()
    }
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = exit.value; scaleX = 1f + (1f - exit.value) * 0.04f; scaleY = 1f + (1f - exit.value) * 0.04f }
            .background(scheme.surface)
            .pointerInput(Unit) { detectTapGestures { finish() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(240.dp)) {
                val c = center
                val l = logo.value
                for (i in 0..2) {
                    val t = (wave + i / 3f) % 1f
                    drawCircle(
                        scheme.primary.copy(alpha = ((1f - t) * 0.30f).coerceIn(0f, 1f)),
                        radius = size.minDimension * (0.30f + 0.20f * t), center = c, style = Stroke(width = 2.5.dp.toPx()),
                    )
                }
                // Material 3 Expressive shape morph (burst -> cookie), slowly rotating behind the glyph.
                val box = size.minDimension * 0.66f * l
                val path = morph.toPath(progress = shapeProgress.value).asComposePath()
                val b = path.getBounds()
                val fit = box / maxOf(b.width, b.height, 0.0001f)
                withTransform({
                    translate(c.x, c.y)
                    rotate(spin, pivot = Offset.Zero)
                    scale(fit, fit, pivot = Offset.Zero)
                    translate(-b.center.x, -b.center.y)
                }) { drawPath(path, scheme.primaryContainer) }
                // Glyph: screen with a play cut-out and a stand.
                val w = size.minDimension * 0.30f * l
                val h = w * 0.68f
                val top = Offset(c.x - w / 2, c.y - h / 2 - 5.dp.toPx() * l)
                drawRoundRect(scheme.onPrimaryContainer, top, Size(w, h), CornerRadius(7.dp.toPx() * l))
                val tri = Path().apply {
                    moveTo(c.x - w * 0.11f, top.y + h * 0.28f); lineTo(c.x + w * 0.17f, top.y + h * 0.5f)
                    lineTo(c.x - w * 0.11f, top.y + h * 0.72f); close()
                }
                drawPath(tri, scheme.primaryContainer)
                drawRoundRect(
                    scheme.onPrimaryContainer, Offset(c.x - w * 0.2f, top.y + h + 7.dp.toPx() * l),
                    Size(w * 0.4f, 4.dp.toPx() * l), CornerRadius(2.dp.toPx()),
                )
            }
            Text(
                "UniversalCast", color = scheme.onSurface, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.graphicsLayer { alpha = title.value; translationY = (1f - title.value) * 28f },
            )
            Text(
                "Cast anything to your TV", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.graphicsLayer { alpha = title.value; translationY = (1f - title.value) * 16f }.padding(top = 4.dp),
            )
        }
    }
}

/** A small dot that gently pulses: marks something live (connected, nearby, connecting). */
@Composable
fun PulseDot(color: Color, size: androidx.compose.ui.unit.Dp = 8.dp) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(
        Modifier.size(size)
            .graphicsLayer { alpha = a; scaleX = 0.8f + 0.4f * a; scaleY = 0.8f + 0.4f * a }
            .background(color, androidx.compose.foundation.shape.CircleShape),
    )
}

/** Presses shrink the element slightly and spring back; observes touches without consuming them. */
fun Modifier.pressScale(): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "press")
    this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    pressed = e.changes.any { it.pressed }
                }
            }
        }
        .graphicsLayer { scaleX = s; scaleY = s }
}
