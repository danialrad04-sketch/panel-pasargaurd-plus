package com.pasarguard.vpn.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pasarguard.vpn.domain.model.ConnectionStatus
import com.pasarguard.vpn.ui.theme.MotionSpec
import com.pasarguard.vpn.ui.theme.PasarGuardTheme
import com.pasarguard.vpn.ui.theme.rememberReduceMotion

@Composable
fun ConnectionDial(
    status: ConnectionStatus,
    actionLabel: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val colors = PasarGuardTheme.colors
    val reducedMotion = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressOffset by animateDpAsState(
        targetValue = if (pressed && !reducedMotion) 3.dp else 0.dp,
        animationSpec = tween(durationMillis = if (reducedMotion) 0 else 120, easing = MotionSpec.standardEasing),
        label = "dial travel"
    )
    val light by animateColorAsState(
        targetValue = when (status) {
            ConnectionStatus.CONNECTED -> colors.statusConnected
            ConnectionStatus.ERROR -> colors.statusDanger
            ConnectionStatus.DISCONNECTING -> scheme.onSurfaceVariant
            else -> scheme.primary
        },
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else MotionSpec.FAST_MS,
            easing = MotionSpec.standardEasing
        ),
        label = "dial status light"
    )
    val busy = status == ConnectionStatus.CONNECTING || status == ConnectionStatus.DISCONNECTING
    val face = scheme.surfaceVariant
    val dark = face.luminance() < 0.5f
    val rim = lerp(face, Color.Black, if (dark) 0.44f else 0.24f)
    val topEdge = lerp(face, Color.White, if (dark) 0.14f else 0.58f)

    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(
                enabled = actionLabel != null,
                role = Role.Button,
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(148.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val scale = size.minDimension / 148f
                val travel = pressOffset.toPx()
                val baseCenter = center
                val capCenter = baseCenter - Offset(0f, 3f * scale) + Offset(0f, travel)

                drawCircle(scheme.surface, radius = 73f * scale, center = baseCenter)
                drawCircle(scheme.outlineVariant, radius = 72.5f * scale, center = baseCenter,
                    style = Stroke(width = scale))
                drawCircle(rim, radius = 57f * scale, center = baseCenter + Offset(0f, 3f * scale))
                drawCircle(face, radius = 57f * scale, center = capCenter)
                drawArc(
                    color = topEdge,
                    startAngle = 196f,
                    sweepAngle = 148f,
                    useCenter = false,
                    topLeft = capCenter - Offset(56f * scale, 56f * scale),
                    size = Size(112f * scale, 112f * scale),
                    style = Stroke(width = scale, cap = StrokeCap.Round)
                )

                // The light above the key shows the tunnel state.
                drawLine(
                    color = light,
                    start = capCenter + Offset(-9f * scale, -39f * scale),
                    end = capCenter + Offset(9f * scale, -39f * scale),
                    strokeWidth = 2f * scale,
                    cap = StrokeCap.Round
                )
            }
            Box(
                modifier = Modifier.offset { IntOffset(0, (pressOffset - 3.dp).roundToPx()) },
                contentAlignment = Alignment.Center
            ) {
                if (busy && !reducedMotion) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = light,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null,
                        tint = scheme.onSurface, modifier = Modifier.size(36.dp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.heightIn(min = 24.dp), contentAlignment = Alignment.Center) {
            if (actionLabel != null) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge,
                    color = scheme.primary, textAlign = TextAlign.Center)
            }
        }
    }
}
