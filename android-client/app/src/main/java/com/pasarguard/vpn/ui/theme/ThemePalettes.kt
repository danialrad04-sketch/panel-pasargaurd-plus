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
    val gradientEnd: Color,
    val companion: Color
)

internal fun ThemePalette.tokens(dark: Boolean): PaletteTokens {
    val shades = when (this) {
        ThemePalette.OBSIDIAN -> PaletteShades(
            canvasDark = 0xFF0D0B11, surfaceDark = 0xFF17141D, containerDark = 0xFF292238,
            canvasLight = 0xFFF7F5FB, surfaceLight = 0xFFFFFFFF, containerLight = 0xFFEDE5FA,
            accentDark = 0xFFBEA4FF, accentLight = 0xFF653AB0,
            gradientStart = 0xFF3F256B, gradientEnd = 0xFF5C3991,
            companionDark = 0xFFBEC8DA, companionLight = 0xFF4F5B70
        )
        ThemePalette.AURORA -> PaletteShades(
            canvasDark = 0xFF0B0D12, surfaceDark = 0xFF15171F, containerDark = 0xFF252238,
            canvasLight = 0xFFF3F7F8, surfaceLight = 0xFFFFFFFF, containerLight = 0xFFEAE5FA,
            accentDark = 0xFFB8A9FA, accentLight = 0xFF593D9A,
            gradientStart = 0xFF3B285F, gradientEnd = 0xFF235958,
            companionDark = 0xFF84DACB, companionLight = 0xFF246359
        )
        ThemePalette.PLUM -> PaletteShades(
            canvasDark = 0xFF100C12, surfaceDark = 0xFF1B151E, containerDark = 0xFF322438,
            canvasLight = 0xFFFCF5FA, surfaceLight = 0xFFFFFFFF, containerLight = 0xFFF1E2F4,
            accentDark = 0xFFD3A6FF, accentLight = 0xFF753E92,
            gradientStart = 0xFF492759, gradientEnd = 0xFF6A3758,
            companionDark = 0xFFF1AFC8, companionLight = 0xFF843F5F
        )
        ThemePalette.OCEAN -> PaletteShades(
            canvasDark = 0xFF091321, surfaceDark = 0xFF142338, containerDark = 0xFF20394D,
            canvasLight = 0xFFF0F6FB, containerLight = 0xFFD7EAF4, surfaceLight = 0xFFFFFFFF,
            accentDark = 0xFF79D9EC, accentLight = 0xFF006477,
            gradientStart = 0xFF234596, gradientEnd = 0xFF076B79
        )
        ThemePalette.JADE -> PaletteShades(
            canvasDark = 0xFF0D1916, surfaceDark = 0xFF162822, containerDark = 0xFF254238,
            canvasLight = 0xFFF0F7F2, containerLight = 0xFFD8EDDF, surfaceLight = 0xFFFFFFFF,
            accentDark = 0xFF8CDBAA, accentLight = 0xFF246347,
            gradientStart = 0xFF21534C, gradientEnd = 0xFF296744
        )
        ThemePalette.DUSK -> PaletteShades(
            canvasDark = 0xFF1B141B, surfaceDark = 0xFF2A1E29, containerDark = 0xFF483240,
            canvasLight = 0xFFFFF5EF, containerLight = 0xFFF5E2DA, surfaceLight = 0xFFFFFCF9,
            accentDark = 0xFFFFB688, accentLight = 0xFF8B492F,
            gradientStart = 0xFF633554, gradientEnd = 0xFF8D4930
        )
    }
    return PaletteTokens(
        accent = Color(if (dark) shades.accentDark else shades.accentLight),
        onAccent = if (dark) Color(shades.canvasDark) else Color.White,
        canvas = Color(if (dark) shades.canvasDark else shades.canvasLight),
        surface = Color(if (dark) shades.surfaceDark else shades.surfaceLight),
        container = Color(if (dark) shades.containerDark else shades.containerLight),
        onContainer = Color(if (dark) shades.canvasLight else shades.canvasDark),
        gradientStart = Color(shades.gradientStart),
        gradientEnd = Color(shades.gradientEnd),
        companion = Color(if (dark) shades.companionDark else shades.companionLight)
    )
}

private data class PaletteShades(
    val canvasDark: Long,
    val surfaceDark: Long,
    val containerDark: Long,
    val canvasLight: Long,
    val containerLight: Long,
    val surfaceLight: Long,
    val accentDark: Long,
    val accentLight: Long,
    val gradientStart: Long,
    val gradientEnd: Long,
    val companionDark: Long = accentDark,
    val companionLight: Long = accentLight
)
