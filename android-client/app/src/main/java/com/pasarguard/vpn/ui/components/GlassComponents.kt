package com.pasarguard.vpn.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pasarguard.vpn.R
import com.pasarguard.vpn.domain.model.ConnectionStatus
import com.pasarguard.vpn.ui.theme.PasarGuardTheme
import com.pasarguard.vpn.ui.theme.Spacing

@Composable
fun DeepSpaceBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), content = content)
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    contentPadding: PaddingValues = PaddingValues(Spacing.md),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(cornerRadius.coerceAtMost(22.dp))
    val border = BorderStroke(1.dp, scheme.outlineVariant)
    if (onClick == null) {
        Surface(modifier = modifier, shape = shape, color = scheme.surface, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(onClick = onClick, modifier = modifier.heightIn(min = 48.dp),
            shape = shape, color = scheme.surface, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
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
    Button(onClick = onClick, enabled = enabled && !isLoading,
        modifier = modifier.heightIn(min = 52.dp), shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        if (isLoading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Spacing.sm))
        } else if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(text)
    }
}

@Composable
fun PulsingDot(
    color: Color,
    isPulsing: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier.size(16.dp), contentAlignment = Alignment.Center) {
        if (isPulsing) Box(Modifier.size(14.dp).background(color.copy(alpha = .19f), CircleShape))
        Box(Modifier.size(7.dp).background(color, CircleShape))
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
    val scheme = MaterialTheme.colorScheme
    val busy = status == ConnectionStatus.CONNECTING || status == ConnectionStatus.DISCONNECTING
    val action = stringResource(when (status) {
        ConnectionStatus.DISCONNECTED -> R.string.tap_to_connect
        ConnectionStatus.CONNECTING -> R.string.connection_cancel
        ConnectionStatus.CONNECTED -> R.string.tap_to_disconnect
        ConnectionStatus.DISCONNECTING -> R.string.status_disconnecting
        ConnectionStatus.ERROR -> R.string.connection_retry
    })
    val activeColor = when (status) {
        ConnectionStatus.CONNECTED -> colors.statusConnected
        ConnectionStatus.ERROR -> colors.statusDanger
        else -> scheme.primary
    }
    val container by animateColorAsState(
        if (status == ConnectionStatus.DISCONNECTED) scheme.primary else scheme.surfaceVariant,
        animationSpec = tween(180), label = "connectionContainer"
    )
    val ink = if (status == ConnectionStatus.DISCONNECTED) scheme.onPrimary else activeColor
    Box(
        modifier.size(154.dp).border(1.dp, scheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(130.dp).clip(CircleShape).background(container)
                .border(1.dp, activeColor.copy(alpha = .6f), CircleShape)
                .clickable(role = Role.Button, enabled = status != ConnectionStatus.DISCONNECTING,
                    onClick = onClick)
                .semantics { contentDescription = action },
            contentAlignment = Alignment.Center
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(32.dp), color = ink, strokeWidth = 2.5.dp)
            } else {
                Icon(Icons.Rounded.PowerSettingsNew, null, tint = ink, modifier = Modifier.size(44.dp))
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
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    Surface(
        modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = shape,
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.selectableGroup().padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            destinations.forEach { destination ->
                val selected = currentRoute == destination.route
                val tint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant
                Column(
                    modifier = Modifier.weight(1f).heightIn(min = 54.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(if (selected) scheme.primaryContainer else Color.Transparent)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(destination.route) })
                        .padding(horizontal = 4.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(destination.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(destination.labelRes), style = MaterialTheme.typography.labelSmall,
                        color = tint, maxLines = 2, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
