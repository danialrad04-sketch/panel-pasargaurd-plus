package com.pasarguard.vpn.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pasarguard.vpn.domain.model.PingHistoryPoint
import com.pasarguard.vpn.domain.model.UsagePoint
import com.pasarguard.vpn.ui.theme.LocalPersianDigits
import com.pasarguard.vpn.ui.theme.PasarGuardTheme
import com.pasarguard.vpn.ui.theme.Spacing
import com.pasarguard.vpn.ui.theme.rememberReduceMotion
import com.pasarguard.vpn.util.Formatters

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
    val reducedMotion = rememberReduceMotion()
    val animatedSweep by animateFloatAsState(
        targetValue = progress.coerceIn(0.03f, 1f) * 360f,
        animationSpec = tween(durationMillis = if (reducedMotion) 1 else 850, easing = FastOutSlowInEasing),
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

/**
 * Compact 10-point latency sparkline rendered in the Server Detail bottom sheet.
 */
@Composable
fun PingSparklineChart(
    history: List<PingHistoryPoint>,
    emptyText: String,
    modifier: Modifier = Modifier
) {
    val colors = PasarGuardTheme.colors
    val valid = history.filter { it.latencyMs > 0 }
    if (valid.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(84.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )
        }
        return
    }

    val maxMs = (valid.maxOf { it.latencyMs }.coerceAtLeast(100)).toFloat()
    val minMs = (valid.minOf { it.latencyMs }.coerceAtLeast(0)).toFloat()
    val span = (maxMs - minMs).coerceAtLeast(40f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(92.dp)
            .padding(vertical = Spacing.xs)
    ) {
        val w = size.width
        val h = size.height
        val count = valid.size
        val stepX = if (count > 1) w / (count - 1).toFloat() else w

        for (i in 0..2) {
            val y = h * (i / 2f)
            drawLine(
                color = colors.textMuted.copy(alpha = 0.16f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        val line = Path()
        val fill = Path()
        valid.forEachIndexed { idx, pt ->
            val x = if (count == 1) w / 2f else idx * stepX
            val norm = ((pt.latencyMs - minMs) / span).coerceIn(0f, 1f)
            val y = h - (norm * (h - 16.dp.toPx())) - 8.dp.toPx()
            if (idx == 0) {
                line.moveTo(x, y)
                fill.moveTo(x, h)
                fill.lineTo(x, y)
            } else {
                line.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(if (count == 1) w / 2f else (count - 1) * stepX, h)
        fill.close()

        drawPath(
            path = fill,
            brush = Brush.verticalGradient(listOf(colors.statusConnected.copy(alpha = 0.30f), Color.Transparent))
        )
        drawPath(
            path = line,
            brush = Brush.horizontalGradient(listOf(colors.heroEnd, colors.statusConnected)),
            style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
        )

        valid.forEachIndexed { idx, pt ->
            val x = if (count == 1) w / 2f else idx * stepX
            val norm = ((pt.latencyMs - minMs) / span).coerceIn(0f, 1f)
            val y = h - (norm * (h - 16.dp.toPx())) - 8.dp.toPx()
            val dotColor = when {
                pt.latencyMs < 150 -> colors.statusConnected
                pt.latencyMs < 350 -> colors.statusWarning
                else -> colors.statusDanger
            }
            drawCircle(color = dotColor, radius = 3.8.dp.toPx(), center = Offset(x, y))
        }
    }
}

/**
 * Animated radial gauge for the Diagnostics through-tunnel download speed test.
 */
@Composable
fun AnimatedSpeedGauge(
    currentMbps: Double,
    maxScaleMbps: Double = 100.0,
    isRunning: Boolean,
    label: String,
    modifier: Modifier = Modifier
) {
    val colors = PasarGuardTheme.colors
    val persianDigits = LocalPersianDigits.current
    val reducedMotion = rememberReduceMotion()
    val targetFraction = (currentMbps / maxScaleMbps).toFloat().coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = if (reducedMotion) 1 else 360, easing = FastOutSlowInEasing),
        label = "speedGauge"
    )

    Box(
        modifier = modifier.size(196.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(196.dp)) {
            val stroke = 14.dp.toPx()
            val pad = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val startAngle = 140f
            val totalSweep = 260f

            drawArc(
                color = colors.textMuted.copy(alpha = 0.22f),
                startAngle = startAngle,
                sweepAngle = totalSweep,
                useCenter = false,
                topLeft = Offset(pad, pad),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(colors.heroStart, colors.heroEnd, colors.statusConnected, colors.heroStart)
                ),
                startAngle = startAngle,
                sweepAngle = (animatedFraction * totalSweep).coerceAtLeast(if (isRunning) 8f else 0f),
                useCenter = false,
                topLeft = Offset(pad, pad),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = Formatters.toPersianDigitsIfNeeded(
                    String.format(java.util.Locale.US, "%.1f", currentMbps.coerceAtLeast(0.0)),
                    persianDigits
                ),
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = colors.textPrimary
            )
            Text(
                text = "Mbps",
                style = MaterialTheme.typography.labelLarge,
                color = colors.heroEnd
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary
            )
        }
    }
}

/**
 * Shimmer skeleton placeholder for loading server cards & subscription stats.
 */
@Composable
fun ShimmerPlaceholder(
    modifier: Modifier = Modifier,
    height: Dp = 76.dp,
    cornerRadius: Dp = 20.dp
) {
    val colors = PasarGuardTheme.colors
    val reducedMotion = rememberReduceMotion()
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shimmerX by transition.animateFloat(
        initialValue = -300f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (reducedMotion) 1 else 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )

    val baseColor = colors.glassSurface
    val highlightColor = colors.textPrimary.copy(alpha = if (colors.isDark) 0.10f else 0.06f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                if (reducedMotion) {
                    Brush.horizontalGradient(listOf(baseColor, baseColor))
                } else {
                    Brush.linearGradient(
                        colors = listOf(baseColor, highlightColor, baseColor),
                        start = Offset(shimmerX, 0f),
                        end = Offset(shimmerX + 260f, 120f)
                    )
                }
            )
    )
}

@Composable
fun CoachTipBanner(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PasarGuardTheme.colors
    GlassCard(modifier = modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = colors.heroEnd,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textPrimary
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    tint = colors.textMuted
                )
            }
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
