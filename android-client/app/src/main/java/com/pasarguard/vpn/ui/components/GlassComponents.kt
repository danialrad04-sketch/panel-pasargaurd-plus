package com.pasarguard.vpn.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pasarguard.vpn.R
import com.pasarguard.vpn.domain.model.ConnectionStatus
import com.pasarguard.vpn.ui.theme.PasarGuardTheme
import com.pasarguard.vpn.ui.theme.Spacing

/**
 * Full-bleed Deep Space canvas with subtle radial ambient light orbs.
 */
@Composable
fun DeepSpaceBackground(
    modifier: Modifier = Modifier,
    accentGlowColor: Color = PasarGuardTheme.colors.heroEnd,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = PasarGuardTheme.colors
    val animatedGlow by animateColorAsState(
        targetValue = accentGlowColor,
        animationSpec = tween(700),
        label = "ambientGlow"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundCanvas)
            .drawBehind {
                val alphaTop = if (colors.isDark) 0.15f else 0.07f
                val alphaBottom = if (colors.isDark) 0.10f else 0.045f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(animatedGlow.copy(alpha = alphaTop), Color.Transparent),
                        center = Offset(size.width * 0.75f, size.height * 0.06f),
                        radius = size.width * 0.85f
                    ),
                    radius = size.width * 0.85f,
                    center = Offset(size.width * 0.75f, size.height * 0.06f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(colors.heroStart.copy(alpha = alphaBottom), Color.Transparent),
                        center = Offset(size.width * 0.15f, size.height * 0.85f),
                        radius = size.width * 0.75f
                    ),
                    radius = size.width * 0.75f,
                    center = Offset(size.width * 0.15f, size.height * 0.85f)
                )
            },
        content = content
    )
}

/**
 * Glassmorphism 2.0 card with translucent fill and vertical white-alpha highlight border.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    contentPadding: PaddingValues = PaddingValues(Spacing.md),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = PasarGuardTheme.colors
    val shape = RoundedCornerShape(cornerRadius)
    val borderBrush = Brush.verticalGradient(
        listOf(colors.glassBorderTop, colors.glassBorderBottom)
    )

    val cardModifier = modifier
        .clip(shape)
        .background(colors.glassSurface.copy(alpha = if (colors.isDark) 0.92f else 0.93f), shape)
        .background(
            Brush.verticalGradient(listOf(colors.heroEnd.copy(alpha = if (colors.isDark) 0.09f else 0.055f), Color.Transparent)),
            shape
        )
        .border(BorderStroke(1.dp, borderBrush), shape)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(contentPadding)

    Column(modifier = cardModifier, content = content)
}

@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    leadingIcon: ImageVector? = null
) {
    val colors = PasarGuardTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "btnScale"
    )
    val shape = RoundedCornerShape(18.dp)
    val brush = Brush.horizontalGradient(
        if (enabled) listOf(colors.heroStart, colors.heroFillEnd)
        else listOf(colors.textMuted.copy(alpha = 0.35f), colors.textMuted.copy(alpha = 0.35f))
    )

    Box(
        modifier = modifier
            .scale(scale)
            .height(54.dp)
            .clip(shape)
            .background(brush)
            .clickable(
                enabled = enabled && !isLoading,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = Color.White,
                strokeWidth = 2.5.dp
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(Spacing.sm))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun PulsingDot(
    color: Color,
    isPulsing: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "dotPulse")
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isPulsing) 2.1f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dotScale"
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = if (isPulsing) 0.6f else 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dotAlpha"
    )

    Box(modifier = modifier.size(16.dp), contentAlignment = Alignment.Center) {
        if (isPulsing) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(color.copy(alpha = pulseAlpha))
            )
        }
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

/**
 * Centerpiece animated VPN power button with concentric breathing rings and spring press feedback.
 */
@Composable
fun ConnectHeroButton(
    status: ConnectionStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PasarGuardTheme.colors
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val cdText = stringResource(R.string.cd_connect_button)

    val buttonScale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "connectScale"
    )

    val isActive = status == ConnectionStatus.CONNECTED
    val isBusy = status == ConnectionStatus.CONNECTING || status == ConnectionStatus.DISCONNECTING

    val startColor by animateColorAsState(
        targetValue = when (status) {
            ConnectionStatus.CONNECTED -> colors.statusConnected
            ConnectionStatus.CONNECTING, ConnectionStatus.DISCONNECTING -> colors.heroFillEnd
            ConnectionStatus.ERROR -> colors.statusDanger
            ConnectionStatus.DISCONNECTED -> colors.heroStart
        },
        animationSpec = tween(450),
        label = "connectStart"
    )
    val endColor by animateColorAsState(
        targetValue = when (status) {
            ConnectionStatus.CONNECTED -> Color(0xFF059669)
            ConnectionStatus.CONNECTING, ConnectionStatus.DISCONNECTING -> colors.heroStart
            ConnectionStatus.ERROR -> Color(0xFFBE123C)
            ConnectionStatus.DISCONNECTED -> colors.heroFillEnd
        },
        animationSpec = tween(450),
        label = "connectEnd"
    )

    val infinite = rememberInfiniteTransition(label = "heroRings")
    val ring1 by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.42f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isBusy) 1100 else 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring1"
    )
    val ring1Alpha by infinite.animateFloat(
        initialValue = 0.42f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isBusy) 1100 else 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring1Alpha"
    )

    Box(
        modifier = modifier
            .size(204.dp)
            .semantics { contentDescription = cdText },
        contentAlignment = Alignment.Center
    ) {
        if (isActive || isBusy) {
            Box(
                modifier = Modifier
                    .size(144.dp)
                    .scale(ring1)
                    .clip(CircleShape)
                    .border(2.dp, startColor.copy(alpha = ring1Alpha), CircleShape)
            )
        }

        // Outer glass halo
        Box(
            modifier = Modifier
                .size(168.dp)
                .clip(CircleShape)
                .background(startColor.copy(alpha = 0.10f))
                .border(1.dp, startColor.copy(alpha = 0.25f), CircleShape)
        )

        // Core interactive button
        Box(
            modifier = Modifier
                .size(138.dp)
                .scale(buttonScale)
                .shadow(
                    elevation = if (isActive) 22.dp else 14.dp,
                    shape = CircleShape,
                    ambientColor = startColor,
                    spotColor = startColor
                )
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(startColor, endColor)))
                .border(
                    width = 2.dp,
                    brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.Transparent)),
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = Color.White,
                    strokeWidth = 3.5.dp
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(54.dp)
                )
            }
        }
    }
}

data class DockDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector
)

/**
 * Floating Pill Bottom Navigation Dock with frosted glass background and pill active indicator.
 */
@Composable
fun FloatingPillBottomDock(
    destinations: List<DockDestination>,
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PasarGuardTheme.colors
    val shape = RoundedCornerShape(32.dp)
    val borderBrush = Brush.verticalGradient(listOf(colors.glassBorderTop, colors.glassBorderBottom))

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = shape,
            color = colors.glassSurface.copy(alpha = if (colors.isDark) 0.90f else 0.95f),
            shadowElevation = 14.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, borderBrush, shape)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                destinations.forEach { dest ->
                    val selected = currentRoute == dest.route
                    val tint by animateColorAsState(
                        targetValue = if (selected) colors.heroEnd else colors.textSecondary,
                        label = "dockTint"
                    )
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(22.dp))
                            .background(
                                if (selected) colors.heroEnd.copy(alpha = 0.14f) else Color.Transparent
                            )
                            .clickable { onSelect(dest.route) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = dest.icon,
                            contentDescription = stringResource(dest.labelRes),
                            tint = tint,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(dest.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = tint,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
