package com.pasarguard.vpn.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
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
import com.pasarguard.vpn.R
import com.pasarguard.vpn.domain.model.AppLanguage
import com.pasarguard.vpn.domain.model.CalendarSystem
import com.pasarguard.vpn.domain.model.ThemePalette
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
    val heroFillEnd: Color,
    val statusConnected: Color,
    val statusWarning: Color,
    val statusDanger: Color,
    val accentViolet: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color
) {
    val glassBorder: Color get() = glassBorderTop
}

private val DarkExtendedColors = PasarGuardExtendedColors(
    isDark = true,
    backgroundCanvas = BrandConfig.darkBackground,
    glassSurface = BrandConfig.darkGlassSurface,
    glassBorderTop = BrandConfig.darkGlassBorderTop,
    glassBorderBottom = BrandConfig.darkGlassBorderBottom,
    heroStart = BrandConfig.accentPrimary,
    heroEnd = BrandConfig.accentSecondary,
    heroFillEnd = BrandConfig.accentSky,
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
    heroFillEnd = BrandConfig.accentSky,
    statusConnected = Color(0xFF059669),
    statusWarning = Color(0xFFD97706),
    statusDanger = Color(0xFFE11D48),
    accentViolet = Color(0xFF7C3AED),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    textMuted = Color(0xFF64748B)
)

val LocalPasarGuardColors = staticCompositionLocalOf { DarkExtendedColors }
val LocalPersianDigits = staticCompositionLocalOf { true }
val LocalCalendarSystem = staticCompositionLocalOf { CalendarSystem.JALALI }

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

private fun resolveBrandFontFamily(language: AppLanguage): FontFamily =
    if (language == AppLanguage.PERSIAN) {
        FontFamily(
            Font(R.font.vazirmatn_regular, FontWeight.Normal),
            Font(R.font.vazirmatn_medium, FontWeight.Medium),
            Font(R.font.vazirmatn_semibold, FontWeight.SemiBold),
            Font(R.font.vazirmatn_bold, FontWeight.Bold)
        )
    } else {
        FontFamily(Font(R.font.inter_regular, FontWeight.Normal))
    }

private fun buildTypography(fontFamily: FontFamily): Typography = Typography(
    displaySmall = TextStyle(
        fontFeatureSettings = "tnum",
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 44.sp
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
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = fontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp
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
    themeMode: ThemeMode = ThemeMode.LIGHT,
    themePalette: ThemePalette = ThemePalette.OCEAN,
    language: AppLanguage = AppLanguage.PERSIAN,
    dynamicColorEnabled: Boolean = false,
    persianDigitsEnabled: Boolean = true,
    calendarSystem: CalendarSystem = CalendarSystem.JALALI,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }

    val context = LocalContext.current
    val fontFamily = remember(language) { resolveBrandFontFamily(language) }
    val typography = remember(fontFamily) { buildTypography(fontFamily) }

    val useDynamic = dynamicColorEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val palette = themePalette.tokens(useDark)
    val baseScheme = if (useDark) DarkColorScheme else LightColorScheme
    val dynamic = when {
        useDynamic && useDark -> dynamicDarkColorScheme(context)
        useDynamic -> dynamicLightColorScheme(context)
        else -> null
    }
    val contrastText = Color(if (useDark) 0xFFEAF3F0 else 0xFF0D1B17)
    val supportingText = Color(if (useDark) 0xFFA2B5AF else 0xFF475A54)
    val neutralContainer = Color(if (useDark) 0xFF16211E else 0xFFEDF3F1)
    val outline = Color(if (useDark) 0xFF526963 else 0xFF718780)
    val baseExtended = if (useDark) DarkExtendedColors else LightExtendedColors
    val colorScheme = baseScheme.copy(
        primary = dynamic?.primary ?: palette.accent,
        onPrimary = dynamic?.onPrimary ?: palette.onAccent,
        primaryContainer = dynamic?.primaryContainer ?: palette.container,
        onPrimaryContainer = dynamic?.onPrimaryContainer ?: palette.onContainer,
        secondary = dynamic?.secondary ?: palette.accent,
        onSecondary = dynamic?.onSecondary ?: palette.onAccent,
        secondaryContainer = dynamic?.secondaryContainer ?: palette.container,
        onSecondaryContainer = dynamic?.onSecondaryContainer ?: palette.onContainer,
        tertiary = Color(if (useDark) 0xFF9A8CFF else 0xFF5845CC),
        background = palette.canvas,
        onBackground = contrastText,
        surface = palette.surface,
        onSurface = contrastText,
        surfaceVariant = neutralContainer,
        onSurfaceVariant = supportingText,
        surfaceContainerLow = palette.surface,
        surfaceContainer = neutralContainer,
        surfaceContainerHigh = neutralContainer,
        surfaceContainerHighest = neutralContainer,
        outline = outline,
        outlineVariant = outline.copy(alpha = .68f),
        surfaceTint = Color.Transparent,
        error = baseExtended.statusDanger
    )
    val extendedColors = baseExtended.copy(
        backgroundCanvas = colorScheme.background,
        glassSurface = colorScheme.surface,
        glassBorderTop = colorScheme.outlineVariant,
        glassBorderBottom = colorScheme.outlineVariant,
        heroStart = palette.gradientStart,
        heroEnd = colorScheme.primary,
        heroFillEnd = palette.gradientEnd,
        textPrimary = contrastText,
        textSecondary = supportingText,
        textMuted = supportingText,
        statusConnected = Color(if (useDark) 0xFF3DDC97 else 0xFF08784C),
        statusWarning = Color(if (useDark) 0xFFFFC24A else 0xFF80521A),
        statusDanger = Color(if (useDark) 0xFFFF6B70 else 0xFFB32636)
    )

    val layoutDirection = if (language == AppLanguage.PERSIAN) LayoutDirection.Rtl else LayoutDirection.Ltr
    val effectivePersianDigits = language == AppLanguage.PERSIAN && persianDigitsEnabled

    CompositionLocalProvider(
        LocalPasarGuardColors provides extendedColors,
        LocalLayoutDirection provides layoutDirection,
        LocalPersianDigits provides effectivePersianDigits,
        LocalCalendarSystem provides calendarSystem
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = androidx.compose.material3.Shapes(
                small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
            ),
            content = content
        )
    }
}

object PasarGuardTheme {
    val colors: PasarGuardExtendedColors
        @Composable get() = LocalPasarGuardColors.current
}
