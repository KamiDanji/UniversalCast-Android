package com.universalcast.receiver.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import kotlin.math.pow

/** Success / caution colours TV Material has no role for. */
@Immutable
class StatusColors(val ok: Color, val warn: Color)

private val TvStatus = StatusColors(ok = Color(0xFF6FDB9A), warn = Color(0xFFFFC857))
private val LocalStatusColors = staticCompositionLocalOf { TvStatus }

val MaterialTheme.status: StatusColors @Composable @ReadOnlyComposable get() = LocalStatusColors.current

/** TV Material dark scheme built from a seed hue (CIE L*C*h tones, same M3 tone assignments as the phone app). */
private fun tvScheme(hue: Float) = run {
    fun p(t: Int) = lch(t.toFloat(), 40f, hue)
    fun s(t: Int) = lch(t.toFloat(), 16f, hue)
    fun t3(t: Int) = lch(t.toFloat(), 26f, (hue + 60f) % 360f)
    fun n(t: Int) = lch(t.toFloat(), 3.5f, hue)
    fun nv(t: Int) = lch(t.toFloat(), 7f, hue)
    darkColorScheme(
        primary = p(80), onPrimary = p(20), primaryContainer = p(30), onPrimaryContainer = p(90),
        inversePrimary = p(40),
        secondary = s(80), onSecondary = s(20), secondaryContainer = s(30), onSecondaryContainer = s(90),
        tertiary = t3(80), onTertiary = t3(20), tertiaryContainer = t3(30), onTertiaryContainer = t3(90),
        background = n(6), onBackground = n(90),
        surface = n(12), onSurface = n(90),
        surfaceVariant = nv(30), onSurfaceVariant = nv(80),
        surfaceTint = p(80),
        inverseSurface = n(90), inverseOnSurface = n(20),
        error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
        border = nv(60), borderVariant = nv(30),
    )
}

// TV text is viewed from ~3 m: larger than phone defaults.
private val typography = Typography(
    displayLarge = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Bold),
    displayMedium = TextStyle(fontSize = 45.sp, fontWeight = FontWeight.SemiBold),
    headlineLarge = TextStyle(fontSize = 38.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 20.sp),
    bodyMedium = TextStyle(fontSize = 18.sp),
    bodySmall = TextStyle(fontSize = 16.sp),
    labelLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun UniversalCastTheme(content: @Composable () -> Unit) {
    val scheme = androidx.compose.runtime.remember { tvScheme(282f) }
    CompositionLocalProvider(LocalStatusColors provides TvStatus) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

/** CIE L*C*h → sRGB; chroma is reduced until the colour fits the sRGB gamut. */
internal fun lch(l: Float, chroma: Float, hueDeg: Float): Color {
    var c = chroma
    while (true) {
        val h = Math.toRadians(hueDeg.toDouble())
        val rgb = labToRgb(l, c * kotlin.math.cos(h).toFloat(), c * kotlin.math.sin(h).toFloat())
        if (rgb != null) return rgb
        if (c <= 0f) { val v = (l / 100f).coerceIn(0f, 1f); return Color(v, v, v) }
        c -= 1f
    }
}

private fun labToRgb(l: Float, a: Float, b: Float): Color? {
    val fy = (l + 16.0) / 116.0
    val fx = fy + a / 500.0
    val fz = fy - b / 200.0
    fun inv(t: Double) = if (t * t * t > 0.008856) t * t * t else (t - 16.0 / 116.0) / 7.787
    val x = 0.95047 * inv(fx); val y = inv(fy); val z = 1.08883 * inv(fz)
    val r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
    val g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
    val bl = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
    fun gamma(c: Double) = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055
    val eps = 0.0005
    if (r < -eps || r > 1 + eps || g < -eps || g > 1 + eps || bl < -eps || bl > 1 + eps) return null
    return Color(gamma(r.coerceIn(0.0, 1.0)).toFloat(), gamma(g.coerceIn(0.0, 1.0)).toFloat(), gamma(bl.coerceIn(0.0, 1.0)).toFloat())
}
