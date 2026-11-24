package com.pasarguard.vpn.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pasarguard.vpn.R
import com.pasarguard.vpn.domain.model.ConnectionStatus
import com.pasarguard.vpn.ui.theme.Spacing

/** Shared full-screen Material canvas. */
@Composable
fun DeepSpaceBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        content = content
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    contentPadding: PaddingValues = PaddingValues(Spacing.md),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(cornerRadius)
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
    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = modifier.heightIn(min = 52.dp),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
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
fun ConnectHeroButton(
    status: ConnectionStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = stringResource(when (status) {
        ConnectionStatus.CONNECTED -> R.string.connection_disconnect
        ConnectionStatus.CONNECTING -> R.string.connection_cancel
        ConnectionStatus.DISCONNECTING -> R.string.status_disconnecting
        ConnectionStatus.ERROR -> R.string.connection_retry
        ConnectionStatus.DISCONNECTED -> R.string.connection_connect
    })
    GradientButton(
        text = label, onClick = onClick, modifier = modifier,
        enabled = status != ConnectionStatus.DISCONNECTING,
        isLoading = status == ConnectionStatus.DISCONNECTING,
        leadingIcon = Icons.Rounded.PowerSettingsNew
    )
}

data class DockDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector
)
