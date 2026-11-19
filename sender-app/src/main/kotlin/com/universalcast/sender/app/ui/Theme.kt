package com.universalcast.sender.app.ui

import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.pow

// ---- user preferences ------------------------------------------------------------------------------------------

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** [hue] is the CIE-LCh hue of the seed; null = Material You colours taken from the wallpaper (Android 12+). */
enum class ColorTheme(val label: String, val hue: Float?, val swatch: Color) {
    DYNAMIC("Wallpaper", null, Color(0xFF8E9AAF)),
    OCEAN("Ocean", 282f, Color(0xFF4A6FE0)),
    VIOLET("Violet", 312f, Color(0xFF8B5CD6)),
    LAGOON("Lagoon", 205f, Color(0xFF1F9AA8)),
    FOREST("Forest", 145f, Color(0xFF3F9D5E)),
    ROSE("Rose", 12f, Color(0xFFD8587A)),
    SUNSET("Sunset", 62f, Color(0xFFD9822B)),
}

/** Persisted look of the app: light/dark mode and colour theme. Process-wide, observed by [SenderTheme]. */
class ThemePrefs private constructor(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("universalcast_ui", Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(read("mode", ThemeMode.SYSTEM))
    private val _color = MutableStateFlow(
        read("color", if (Build.VERSION.SDK_INT >= 31) ColorTheme.DYNAMIC else ColorTheme.OCEAN)
            .let { if (it == ColorTheme.DYNAMIC && Build.VERSION.SDK_INT < 31) ColorTheme.OCEAN else it },
    )
    val mode: StateFlow<ThemeMode> = _mode
    val color: StateFlow<ColorTheme> = _color

    fun setMode(m: ThemeMode) { _mode.value = m; prefs.edit().putString("mode", m.name).apply() }
    fun setColor(c: ColorTheme) { _color.value = c; prefs.edit().putString("color", c.name).apply() }

    private inline fun <reified E : Enum<E>> read(key: String, default: E): E =
        runCatching { enumValueOf<E>(prefs.getString(key, null) ?: return default) }.getOrDefault(default)

    companion object {
        @Volatile private var instance: ThemePrefs? = null
        fun get(ctx: Context): ThemePrefs = instance ?: synchronized(this) { instance ?: ThemePrefs(ctx).also { instance = it } }
    }
}

// ---- colour schemes ---------------------------------------------------------------------------------------------

/** Status colours Material 3 has no role for (success / caution), tuned for contrast in both modes. */
@Immutable
class StatusColors(val ok: Color, val onOk: Color, val warn: Color, val onWarn: Color)

private val LightStatus = StatusColors(ok = Color(0xFF16693D), onOk = Color.White, warn = Color(0xFF8A5A00), onWarn = Color.White)
private val DarkStatus = StatusColors(ok = Color(0xFF6FDB9A), onOk = Color(0xFF00391C), warn = Color(0xFFFFC857), onWarn = Color(0xFF3F2E00))
private val LocalStatusColors = staticCompositionLocalOf { DarkStatus }

val MaterialTheme.status: StatusColors @Composable @ReadOnlyComposable get() = LocalStatusColors.current

/**
 * Builds a full Material 3 [ColorScheme] from a seed hue using the standard M3 tone assignments
 * (primary T40/T80, containers T90/T30, neutral surfaces T98/T6 …). Tones are CIE L*, so contrast matches the spec.
 */
fun schemeFromSeed(hue: Float, dark: Boolean): ColorScheme {
    fun p(tone: Int) = lch(tone.toFloat(), 40f, hue)
    fun s(tone: Int) = lch(tone.toFloat(), 16f, hue)
    fun t(tone: Int) = lch(tone.toFloat(), 26f, (hue + 60f) % 360f)
    fun n(tone: Int) = lch(tone.toFloat(), 3.5f, hue)
    fun nv(tone: Int) = lch(tone.toFloat(), 7f, hue)
    val err = if (dark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A)
    val onErr = if (dark) Color(0xFF690005) else Color.White
    val errC = if (dark) Color(0xFF93000A) else Color(0xFFFFDAD6)
    val onErrC = if (dark) Color(0xFFFFDAD6) else Color(0xFF410002)
    return if (dark) darkColorScheme(
        primary = p(80), onPrimary = p(20), primaryContainer = p(30), onPrimaryContainer = p(90),
        secondary = s(80), onSecondary = s(20), secondaryContainer = s(30), onSecondaryContainer = s(90),
        tertiary = t(80), onTertiary = t(20), tertiaryContainer = t(30), onTertiaryContainer = t(90),
        error = err, onError = onErr, errorContainer = errC, onErrorContainer = onErrC,
        background = n(6), onBackground = n(90), surface = n(6), onSurface = n(90),
        surfaceVariant = nv(30), onSurfaceVariant = nv(80), outline = nv(60), outlineVariant = nv(30),
        inverseSurface = n(90), inverseOnSurface = n(20), inversePrimary = p(40), surfaceTint = p(80),
        surfaceBright = n(24), surfaceDim = n(6), surfaceContainerLowest = n(4), surfaceContainerLow = n(10),
        surfaceContainer = n(12), surfaceContainerHigh = n(17), surfaceContainerHighest = n(22),
    ) else lightColorScheme(
        primary = p(40), onPrimary = p(100), primaryContainer = p(90), onPrimaryContainer = p(10),
        secondary = s(40), onSecondary = s(100), secondaryContainer = s(90), onSecondaryContainer = s(10),
        tertiary = t(40), onTertiary = t(100), tertiaryContainer = t(90), onTertiaryContainer = t(10),
        error = err, onError = onErr, errorContainer = errC, onErrorContainer = onErrC,
        background = n(98), onBackground = n(10), surface = n(98), onSurface = n(10),
        surfaceVariant = nv(90), onSurfaceVariant = nv(30), outline = nv(50), outlineVariant = nv(80),
        inverseSurface = n(20), inverseOnSurface = n(95), inversePrimary = p(80), surfaceTint = p(40),
        surfaceBright = n(98), surfaceDim = n(87), surfaceContainerLowest = n(100), surfaceContainerLow = n(96),
        surfaceContainer = n(94), surfaceContainerHigh = n(92), surfaceContainerHighest = n(90),
    )
}

/** CIE L*C*h → sRGB; chroma is reduced until the colour fits the sRGB gamut. */
internal fun lch(l: Float, chroma: Float, hueDeg: Float): Color {
    if (l >= 99.9f) return Color.White
    var c = chroma
    while (true) {
        val rgb = labToRgb(l, c * kotlin.math.cos(Math.toRadians(hueDeg.toDouble())).toFloat(), c * kotlin.math.sin(Math.toRadians(hueDeg.toDouble())).toFloat())
        if (rgb != null || c <= 0f) return rgb ?: labToRgbClamped(l)
        c -= 1f
    }
}

private fun labToRgbClamped(l: Float): Color { val v = ((l / 100f).coerceIn(0f, 1f)); return Color(v, v, v) }

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

// ---- theme ------------------------------------------------------------------------------------------------------

@Composable
fun SenderTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ThemePrefs.get(ctx) }
    val mode by prefs.mode.collectAsState()
    val colorTheme by prefs.color.collectAsState()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = when {
        colorTheme == ColorTheme.DYNAMIC && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        else -> remember(colorTheme, dark) { schemeFromSeed((colorTheme.hue ?: ColorTheme.OCEAN.hue!!), dark) }
    }

    // Edge-to-edge with system-bar icons that follow the chosen (not the system) mode.
    val activity = ctx as? ComponentActivity
    DisposableEffect(activity, dark) {
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
        )
        onDispose {}
    }

    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

// ---- shared building blocks ---------------------------------------------------------------------------------------

/** App backdrop: the plain Material 3 surface. */
@Composable
fun Modifier.appBackground(): Modifier = this.background(MaterialTheme.colorScheme.surface)

/** A Material 3 filled card used for every content group. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth().reveal().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** Small tonal status label (optionally with a pulsing dot for something live). */
@Composable
fun Chip(text: String, color: Color, live: Boolean = false) {
    Surface(shape = CircleShape, color = color.copy(alpha = 0.14f)) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (live) PulseDot(color)
            Text(text, color = color, style = MaterialTheme.typography.labelMedium)
        }
    }
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
