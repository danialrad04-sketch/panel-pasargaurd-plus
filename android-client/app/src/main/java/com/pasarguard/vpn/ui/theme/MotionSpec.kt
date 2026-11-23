package com.pasarguard.vpn.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Centralized motion & physics tokens honoring system Reduce Motion preferences.
 */
object MotionSpec {
    const val FAST_MS = 180
    const val STANDARD_MS = 320
    const val EMPHASIZED_MS = 520

    val standardEasing: Easing = FastOutSlowInEasing

    fun <T> snappySpring(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    fun <T> bouncySpring(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    fun <T> standardTween(reducedMotion: Boolean = false): TweenSpec<T> =
        tween(durationMillis = if (reducedMotion) 1 else STANDARD_MS, easing = standardEasing)

    fun <T> emphasizedTween(reducedMotion: Boolean = false): TweenSpec<T> =
        tween(durationMillis = if (reducedMotion) 1 else EMPHASIZED_MS, easing = standardEasing)

    fun isReduceMotionEnabled(context: Context): Boolean = runCatching {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        scale == 0f
    }.getOrDefault(false)
}

@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { MotionSpec.isReduceMotionEnabled(context) }
}
