package com.pasarguard.vpn.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pasarguard.vpn.domain.model.UsagePoint
import com.pasarguard.vpn.ui.theme.PasarGuardTheme
import com.pasarguard.vpn.ui.theme.Spacing

/**
 * Animated circular quota/expiry progress ring card that shifts color automatically:
 * Healthy (>30%) -> Emerald/Cyan, Warning (15-30%) -> Amber, Critical (<15%) -> Crimson.
 */
@Composable
fun ProgressRingCard(
    title: String,
    primaryValue: String,
    subtitle: String,
    progress: Float,
    modifier: Modifier = Modifier,
    isWarning: Boolean = false,
    isDanger: Boolean = false
) {
    val colors = PasarGuardTheme.colors
    val animatedSweep by animateFloatAsState(
        targetValue = progress.coerceIn(0.03f, 1f) * 360f,
        animationSpec = tween(durationMillis = 850, easing = FastOutSlowInEasing),
        label = "ringSweep"
    )
    val ringColor = when {
        isDanger -> colors.statusDanger
        isWarning -> colors.statusWarning
        else -> colors.heroEnd
    }

    GlassCard(modifier = modifier, cornerRadius = 24.dp) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.size(96.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(96.dp)) {
                    val stroke = 8.dp.toPx()
                    val pad = stroke / 2f
                    val arcSize = Size(size.width - stroke, size.height - stroke)

                    drawArc(
                        color = colors.textMuted.copy(alpha = 0.22f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(pad, pad),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                    drawArc(
                        brush = Brush.sweepGradient(listOf(ringColor, colors.heroStart, ringColor)),
                        startAngle = -90f,
                        sweepAngle = animatedSweep,
                        useCenter = false,
                        topLeft = Offset(pad, pad),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
                Text(
                    text = primaryValue,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Minimal Compose Canvas area-line chart for PasarGuard `/sub/{token}/usage` history.
 */
@Composable
fun UsageHistoryChart(
    points: List<UsagePoint>,
    emptyText: String,
    modifier: Modifier = Modifier
) {
    val colors = PasarGuardTheme.colors
    if (points.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(140.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    val maxVal = (points.maxOfOrNull { it.bytes } ?: 1L).coerceAtLeast(1L)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .padding(vertical = Spacing.sm)
    ) {
        val w = size.width
        val h = size.height
        val count = points.size
        val stepX = if (count > 1) w / (count - 1).toFloat() else w

        // Horizontal guide lines
        for (i in 0..3) {
            val y = h * (i / 3f)
            drawLine(
                color = colors.textMuted.copy(alpha = 0.16f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        val linePath = Path()
        val fillPath = Path()

        points.forEachIndexed { idx, pt ->
            val x = if (count == 1) w / 2f else idx * stepX
            val normalized = (pt.bytes.toDouble() / maxVal.toDouble()).toFloat().coerceIn(0f, 1f)
            val y = h - (normalized * (h - 16.dp.toPx())) - 8.dp.toPx()
            if (idx == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(if (count == 1) w / 2f else (count - 1) * stepX, h)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                listOf(colors.heroEnd.copy(alpha = 0.34f), Color.Transparent)
            )
        )
        drawPath(
            path = linePath,
            brush = Brush.horizontalGradient(listOf(colors.heroStart, colors.heroEnd)),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )

        points.forEachIndexed { idx, pt ->
            val x = if (count == 1) w / 2f else idx * stepX
            val normalized = (pt.bytes.toDouble() / maxVal.toDouble()).toFloat().coerceIn(0f, 1f)
            val y = h - (normalized * (h - 16.dp.toPx())) - 8.dp.toPx()
            drawCircle(color = colors.heroEnd, radius = 4.dp.toPx(), center = Offset(x, y))
        }
    }
}

@Composable
fun EmptyStateView(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.CloudOff,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val colors = PasarGuardTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.heroEnd.copy(alpha = 0.8f),
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.md))
            OutlinedButton(onClick = onAction) {
                Text(text = actionLabel)
            }
        }
    }
}
