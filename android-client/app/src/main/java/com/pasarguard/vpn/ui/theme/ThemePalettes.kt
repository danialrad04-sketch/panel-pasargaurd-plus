package com.pasarguard.vpn.ui.theme

import androidx.compose.ui.graphics.Color
import com.pasarguard.vpn.domain.model.ThemePalette

internal data class PaletteTokens(
    val accent: Color,
    val onAccent: Color,
    val canvas: Color,
    val surface: Color,
    val container: Color,
    val onContainer: Color,
    val gradientStart: Color,
    val gradientEnd: Color
)

internal fun ThemePalette.tokens(dark: Boolean): PaletteTokens {
    val shades = when (this) {
        ThemePalette.OCEAN -> PaletteShades(
            containerDark = 0xFF20394D,
            containerLight = 0xFFD7EAF4,
            accentDark = 0xFF79D9EC, accentLight = 0xFF006477,
            gradientStart = 0xFF234596, gradientEnd = 0xFF076B79
        )
        ThemePalette.JADE -> PaletteShades(
            containerDark = 0xFF254238,
            containerLight = 0xFFD8EDDF,
            accentDark = 0xFF8CDBAA, accentLight = 0xFF246347,
            gradientStart = 0xFF21534C, gradientEnd = 0xFF296744
        )
        ThemePalette.DUSK -> PaletteShades(
            containerDark = 0xFF483240,
            containerLight = 0xFFF5E2DA,
            accentDark = 0xFFFFB688, accentLight = 0xFF8B492F,
            gradientStart = 0xFF633554, gradientEnd = 0xFF8D4930
        )
    }
    return PaletteTokens(
        accent = Color(if (dark) shades.accentDark else shades.accentLight),
        onAccent = if (dark) Color(0xFF17181A) else Color.White,
        canvas = Color(if (dark) 0xFF17181A else 0xFFF5F4F1),
        surface = Color(if (dark) 0xFF212224 else 0xFFFFFEFC),
        container = Color(if (dark) shades.containerDark else shades.containerLight),
        onContainer = Color(if (dark) 0xFFF5F4F1 else 0xFF202124),
        gradientStart = Color(shades.gradientStart),
        gradientEnd = Color(shades.gradientEnd)
    )
}

private data class PaletteShades(
    val containerDark: Long,
    val containerLight: Long,
    val accentDark: Long,
    val accentLight: Long,
    val gradientStart: Long,
    val gradientEnd: Long
)
