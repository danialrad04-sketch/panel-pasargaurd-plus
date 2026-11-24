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
            containerDark = 0xFF392858,
            containerLight = 0xFFEEE4FF,
            accentDark = 0xFFD0B4FF, accentLight = 0xFF6534AE,
            gradientStart = 0xFF5025A3, gradientEnd = 0xFF42398B
        )
        ThemePalette.JADE -> PaletteShades(
            containerDark = 0xFF153E44,
            containerLight = 0xFFD0F4F2,
            accentDark = 0xFF76DBD6, accentLight = 0xFF00696E,
            gradientStart = 0xFF125B69, gradientEnd = 0xFF00676B
        )
        ThemePalette.DUSK -> PaletteShades(
            containerDark = 0xFF542B40,
            containerLight = 0xFFFFE1EB,
            accentDark = 0xFFFFAECC, accentLight = 0xFF92344F,
            gradientStart = 0xFF783351, gradientEnd = 0xFF9B3D52
        )
    }
    return PaletteTokens(
        accent = Color(if (dark) shades.accentDark else shades.accentLight),
        onAccent = if (dark) Color(0xFF191722) else Color.White,
        canvas = Color(if (dark) 0xFF191722 else 0xFFF8F6FC),
        surface = Color(if (dark) 0xFF24212E else 0xFFFFFDFF),
        container = Color(if (dark) shades.containerDark else shades.containerLight),
        onContainer = Color(if (dark) 0xFFF8F6FC else 0xFF202124),
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
