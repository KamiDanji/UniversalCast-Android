package com.universalcast.sender.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

/** The seeded colour themes must stay readable: every foreground/background pair meets WCAG AA (4.5:1). */
class ThemeTest {
    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble(); val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    @Test
    fun seededSchemesMeetContrast() {
        for (theme in ColorTheme.entries) {
            val hue = theme.hue ?: continue // DYNAMIC uses the system's wallpaper scheme
            for (dark in listOf(false, true)) {
                val s = schemeFromSeed(hue, dark)
                val pairs = mapOf(
                    "primary" to (s.primary to s.onPrimary),
                    "primaryContainer" to (s.primaryContainer to s.onPrimaryContainer),
                    "secondaryContainer" to (s.secondaryContainer to s.onSecondaryContainer),
                    "tertiaryContainer" to (s.tertiaryContainer to s.onTertiaryContainer),
                    "surface" to (s.surface to s.onSurface),
                    "surfaceContainerHigh" to (s.surfaceContainerHigh to s.onSurface),
                    "surfaceVariant text" to (s.surfaceContainerHigh to s.onSurfaceVariant),
                    "error" to (s.error to s.onError),
                )
                for ((name, p) in pairs) {
                    val c = contrast(p.first, p.second)
                    assertTrue(c >= 4.5, "${theme.name} dark=$dark $name contrast $c < 4.5")
                }
            }
        }
    }

    @Test
    fun darkSchemesAreDarkerThanLight() {
        for (theme in ColorTheme.entries) {
            val hue = theme.hue ?: continue
            assertTrue(schemeFromSeed(hue, true).surface.luminance() < schemeFromSeed(hue, false).surface.luminance())
        }
    }

    @Test
    fun statusColorsReadableOnSurfaces() {
        val light = schemeFromSeed(ColorTheme.OCEAN.hue!!, false)
        val dark = schemeFromSeed(ColorTheme.OCEAN.hue!!, true)
        assertTrue(contrast(Color(0xFF16693D), light.surfaceContainerHigh) >= 4.5)
        assertTrue(contrast(Color(0xFF8A5A00), light.surfaceContainerHigh) >= 4.5)
        assertTrue(contrast(Color(0xFF6FDB9A), dark.surfaceContainerHigh) >= 4.5)
        assertTrue(contrast(Color(0xFFFFC857), dark.surfaceContainerHigh) >= 4.5)
    }
}
