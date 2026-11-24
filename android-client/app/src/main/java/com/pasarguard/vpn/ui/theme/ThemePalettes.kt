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
            containerDark = 0xFF253447,
            containerLight = 0xFFDEE8F5,
            accentDark = 0xFFAEC7E8, accentLight = 0xFF34577C,
            gradientStart = 0xFF2C4056, gradientEnd = 0xFF344A63
        )
        ThemePalette.JADE -> PaletteShades(
            containerDark = 0xFF203936,
            containerLight = 0xFFD9EBE6,
            accentDark = 0xFF99CEC1, accentLight = 0xFF2F6259,
            gradientStart = 0xFF25443F, gradientEnd = 0xFF35564E
        )
        ThemePalette.DUSK -> PaletteShades(
            containerDark = 0xFF352F48,
            containerLight = 0xFFEAE5F2,
            accentDark = 0xFFC5B8DE, accentLight = 0xFF605379,
            gradientStart = 0xFF40354D, gradientEnd = 0xFF514464
        )
    }
    return PaletteTokens(
        accent = Color(if (dark) shades.accentDark else shades.accentLight),
        onAccent = if (dark) Color(0xFF12171D) else Color.White,
        canvas = Color(if (dark) 0xFF12171D else 0xFFF3F5F7),
        surface = Color(if (dark) 0xFF1B222A else 0xFFFFFFFF),
        container = Color(if (dark) shades.containerDark else shades.containerLight),
        onContainer = Color(if (dark) 0xFFF3F5F7 else 0xFF202124),
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
