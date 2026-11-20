package com.pasarguard.vpn

import androidx.compose.ui.graphics.Color

/**
 * Single source of truth for white-label branding, panel endpoints, support links,
 * and visual accent tokens. Update this file (or gradle.properties / local.properties)
 * to rebrand the entire Android application.
 */
object BrandConfig {
    // Panel & Deep Link Endpoints (populated from BuildConfig; zero hardcoded secrets)
    val defaultBaseUrl: String = BuildConfig.DEFAULT_BASE_URL.trimEnd('/')
    val appName: String = BuildConfig.BRAND_NAME
    val supportTelegramUrl: String = BuildConfig.SUPPORT_TELEGRAM_URL
    val supportWhatsappUrl: String = BuildConfig.SUPPORT_WHATSAPP_URL
    val paymentCallbackScheme: String = BuildConfig.PAYMENT_CALLBACK_SCHEME
    val paymentCallbackUri: String = "$paymentCallbackScheme://payment/callback"

    // Default startup preferences
    const val defaultLanguageCode: String = "fa" // Persian RTL default ("fa" or "en")

    // Deep Space Dark + Cyan/Blue Brand Palette (override here for your brand)
    val darkBackground = Color(0xFF07090E)
    val darkSurfaceElevated = Color(0xFF0F1422)
    val darkGlassSurface = Color(0xB3161C2D) // rgba(22, 28, 45, 0.70)
    val darkGlassBorderTop = Color(0x2EFFFFFF)
    val darkGlassBorderBottom = Color(0x05FFFFFF)

    val lightBackground = Color(0xFFF3F6FB)
    val lightSurfaceElevated = Color(0xFFFFFFFF)
    val lightGlassSurface = Color(0xE6FFFFFF)
    val lightGlassBorderTop = Color(0x240F172A)
    val lightGlassBorderBottom = Color(0x080F172A)

    // Brand Hero Gradient (Electric Blue -> Sky Cyan)
    val accentPrimary = Color(0xFF1D4ED8)
    val accentSecondary = Color(0xFF06B6D4)
    val accentSky = Color(0xFF0284C7)

    // Semantic Telemetry & Status Colors
    val statusConnected = Color(0xFF10B981) // Emerald Neon
    val statusWarning = Color(0xFFF59E0B)   // Amber Glow
    val statusDanger = Color(0xFFF43F5E)    // Crimson Rose
    val accentViolet = Color(0xFF8B5CF6)

    // Background Sync & Alert Thresholds
    const val autoRefreshIntervalHours: Long = 6L
    const val expiryWarningDaysThreshold: Int = 3
    const val lowTrafficWarningPercentThreshold: Int = 15
    const val clashApiPort: Int = 9090
}
