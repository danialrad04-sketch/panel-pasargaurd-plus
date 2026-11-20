package com.pasarguard.vpn.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pasarguard.vpn.BrandConfig
import com.pasarguard.vpn.domain.model.AppLanguage
import com.pasarguard.vpn.domain.model.ThemeMode

/**
 * Consistent 8dp spatial scale used across every screen and component.
 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
}

@Immutable
data class PasarGuardExtendedColors(
    val isDark: Boolean,
    val backgroundCanvas: Color,
    val glassSurface: Color,
    val glassBorderTop: Color,
    val glassBorderBottom: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val statusConnected: Color,
    val statusWarning: Color,
    val statusDanger: Color,
    val accentViolet: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color
)

private val DarkExtendedColors = PasarGuardExtendedColors(
    isDark = true,
    backgroundCanvas = BrandConfig.darkBackground,
    glassSurface = BrandConfig.darkGlassSurface,
    glassBorderTop = BrandConfig.darkGlassBorderTop,
    glassBorderBottom = BrandConfig.darkGlassBorderBottom,
    heroStart = BrandConfig.accentPrimary,
    heroEnd = BrandConfig.accentSecondary,
    statusConnected = BrandConfig.statusConnected,
    statusWarning = BrandConfig.statusWarning,
    statusDanger = BrandConfig.statusDanger,
    accentViolet = BrandConfig.accentViolet,
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFF94A3B8),
    textMuted = Color(0xFF64748B)
)

private val LightExtendedColors = PasarGuardExtendedColors(
    isDark = false,
    backgroundCanvas = BrandConfig.lightBackground,
    glassSurface = BrandConfig.lightGlassSurface,
    glassBorderTop = BrandConfig.lightGlassBorderTop,
    glassBorderBottom = BrandConfig.lightGlassBorderBottom,
    heroStart = BrandConfig.accentPrimary,
    heroEnd = BrandConfig.accentSky,
    statusConnected = Color(0xFF059669),
    statusWarning = Color(0xFFD97706),
    statusDanger = Color(0xFFE11D48),
    accentViolet = Color(0xFF7C3AED),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    textMuted = Color(0xFF64748B)
)

val LocalPasarGuardColors = staticCompositionLocalOf { DarkExtendedColors }

private val DarkColorScheme = darkColorScheme(
    primary = BrandConfig.accentSecondary,
    onPrimary = Color(0xFF02131A),
    primaryContainer = Color(0xFF13294B),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = BrandConfig.accentPrimary,
    onSecondary = Color.White,
    tertiary = BrandConfig.statusConnected,
    background = BrandConfig.darkBackground,
    onBackground = Color(0xFFF8FAFC),
    surface = BrandConfig.darkSurfaceElevated,
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF161C2D),
    onSurfaceVariant = Color(0xFF94A3B8),
    error = BrandConfig.statusDanger,
    outline = Color(0x33FFFFFF)
)

private val LightColorScheme = lightColorScheme(
    primary = BrandConfig.accentPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = BrandConfig.accentSky,
    onSecondary = Color.White,
    tertiary = Color(0xFF059669),
    background = BrandConfig.lightBackground,
    onBackground = Color(0xFF0F172A),
    surface = BrandConfig.lightSurfaceElevated,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    error = Color(0xFFE11D48),
    outline = Color(0x240F172A)
)

/**
 * Loads bundled Vazirmatn (for Persian) or Inter (for English) fonts from `res/font/`
 * when present, falling back gracefully to system sans-serif so the project builds cleanly
 * immediately out of the box.
 */
private fun resolveBrandFontFamily(context: Context, language: AppLanguage): FontFamily {
    val prefix = if (language == AppLanguage.PERSIAN) "vazirmatn" else "inter"
    val regularId = context.resources.getIdentifier("${prefix}_regular", "font", context.packageName)
    val mediumId = context.resources.getIdentifier("${prefix}_medium", "font", context.packageName)
    val semiBoldId = context.resources.getIdentifier("${prefix}_semibold", "font", context.packageName)
    val boldId = context.resources.getIdentifier("${prefix}_bold", "font", context.packageName)

    if (regularId == 0) return FontFamily.SansSerif
    val fonts = buildList {
        add(Font(regularId, FontWeight.Normal))
        if (mediumId != 0) add(Font(mediumId, FontWeight.Medium))
        if (semiBoldId != 0) add(Font(semiBoldId, FontWeight.SemiBold))
        if (boldId != 0) add(Font(boldId, FontWeight.Bold))
    }
    return FontFamily(fonts)
}

private fun buildTypography(fontFamily: FontFamily): Typography = Typography(
    displaySmall = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    titleLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelSmall = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
)

@Composable
fun PasarGuardVpnTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    language: AppLanguage = AppLanguage.PERSIAN,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }

    val context = LocalContext.current
    val fontFamily = remember(language) { resolveBrandFontFamily(context, language) }
    val typography = remember(fontFamily) { buildTypography(fontFamily) }
    val extendedColors = if (useDark) DarkExtendedColors else LightExtendedColors
    val layoutDirection = if (language == AppLanguage.PERSIAN) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(
        LocalPasarGuardColors provides extendedColors,
        LocalLayoutDirection provides layoutDirection
    ) {
        MaterialTheme(
            colorScheme = if (useDark) DarkColorScheme else LightColorScheme,
            typography = typography,
            content = content
        )
    }
}

object PasarGuardTheme {
    val colors: PasarGuardExtendedColors
        @Composable get() = LocalPasarGuardColors.current
}
