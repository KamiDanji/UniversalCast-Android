package com.universalcast.receiver.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** App-wide backdrop: deep navy gradient with soft accent glows in two corners. */
fun Modifier.appBackground(): Modifier = this
    .background(Brush.verticalGradient(listOf(Color(0xFF111A2E), Palette.Background, Color(0xFF070A10))))
    .drawBehind {
        drawRect(
            Brush.radialGradient(
                listOf(Palette.Accent.copy(alpha = 0.20f), Color.Transparent),
                center = Offset(size.width * 0.9f, 0f), radius = size.maxDimension * 0.65f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(Color(0xFF8A5BFF).copy(alpha = 0.10f), Color.Transparent),
                center = Offset(0f, size.height), radius = size.maxDimension * 0.6f,
            ),
        )
    }

/** Fades and slides a composable in once, [delayMs] after it first appears. */
@Composable
fun Modifier.reveal(delayMs: Int = 0): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(delayMs.toLong()); p.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
    return this.graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 36f }
}

/** Launch animation: the screen-with-play logo springs in, cast waves ripple out of it, the name fades up, then it dissolves. */
@Composable
fun SplashOverlay(onDone: () -> Unit) {
    val logo = remember { Animatable(0f) }
    val title = remember { Animatable(0f) }
    val exit = remember { Animatable(1f) }
    val waves = rememberInfiniteTransition(label = "waves")
    val wave by waves.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "wave")
    LaunchedEffect(Unit) {
        logo.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 140f))
        title.animateTo(1f, tween(450))
        delay(650)
        exit.animateTo(0f, tween(380))
        onDone()
    }
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = exit.value }.appBackground(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(260.dp)) {
                val c = center
                val l = logo.value
                for (i in 0..2) {
                    val t = (wave + i / 3f) % 1f
                    drawCircle(
                        Palette.Accent.copy(alpha = ((1f - t) * 0.35f * l).coerceIn(0f, 1f)), radius = size.minDimension * (0.28f + 0.22f * t),
                        center = c, style = Stroke(width = 3.dp.toPx()),
                    )
                }
                val w = size.minDimension * 0.62f * l
                val h = w * 0.68f
                val tl = Offset(c.x - w / 2, c.y - h / 2 - 6.dp.toPx())
                drawRoundRect(
                    Brush.linearGradient(listOf(Color(0xFF7AA2FF), Palette.Accent, Color(0xFF8A5BFF)), tl, Offset(tl.x + w, tl.y + h)),
                    tl, Size(w, h), CornerRadius(14.dp.toPx() * l),
                )
                val cy = c.y - 6.dp.toPx()
                val tri = Path().apply {
                    moveTo(c.x - w * 0.10f, cy - h * 0.22f); lineTo(c.x + w * 0.16f, cy)
                    lineTo(c.x - w * 0.10f, cy + h * 0.22f); close()
                }
                drawPath(tri, Palette.Background.copy(alpha = l.coerceIn(0f, 1f)))
                drawRoundRect(
                    Palette.Accent.copy(alpha = l.coerceIn(0f, 1f)), Offset(c.x - w * 0.2f, tl.y + h + 8.dp.toPx()),
                    Size(w * 0.4f, 5.dp.toPx()), CornerRadius(3.dp.toPx()),
                )
            }
            Text(
                "UniversalCast", color = Palette.Text, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp,
                modifier = Modifier.graphicsLayer { alpha = title.value; translationY = (1f - title.value) * 24f },
            )
            Text(
                "Cast anything to your TV", color = Palette.TextDim, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.graphicsLayer { alpha = title.value }.padding(top = 6.dp),
            )
        }
    }
}
