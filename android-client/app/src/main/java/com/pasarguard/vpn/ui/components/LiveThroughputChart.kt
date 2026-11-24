package com.pasarguard.vpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

internal data class ThroughputSample(val download: Long, val upload: Long)

@Composable
internal fun rememberThroughput(sessionStartedAt: Long?, download: Long, upload: Long, capacity: Int = 60): List<ThroughputSample> {
    val samples = remember(sessionStartedAt, capacity) { mutableStateListOf<ThroughputSample>() }
    val current: State<ThroughputSample> = rememberUpdatedState(ThroughputSample(download, upload))
    LaunchedEffect(sessionStartedAt, capacity) {
        while (true) {
            if (samples.size == capacity) samples.removeAt(0)
            samples.add(current.value)
            delay(1_000)
        }
    }
    return samples
}

@Composable
internal fun LiveThroughputChart(samples: List<ThroughputSample>, description: String, windowSeconds: Int = 60) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val download = MaterialTheme.colorScheme.primary
    val upload = MaterialTheme.colorScheme.tertiary
    Canvas(
        Modifier.fillMaxWidth().height(112.dp)
            .semantics { contentDescription = description }
            .drawWithCache {
                val visible = samples.takeLast(windowSeconds)
                val peak = visible.maxOfOrNull { maxOf(it.download, it.upload) } ?: 0L
                val ceiling = (peak.toFloat() * 1.2f).coerceAtLeast(1024f)
                val stroke = 2.dp.toPx()
                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
                val chartHeight = (size.height - stroke * 2).coerceAtLeast(0f)
                val step = (size.width - stroke * 2).coerceAtLeast(0f) / (windowSeconds - 1)
                val endX = size.width - stroke
                fun series(read: (ThroughputSample) -> Long): Pair<Path, Offset> {
                    val path = Path()
                    var last = Offset(endX, size.height - stroke)
                    visible.forEachIndexed { index, sample ->
                        last = Offset(endX - (visible.lastIndex - index) * step,
                            stroke + chartHeight * (1f - (read(sample) / ceiling).coerceIn(0f, 1f)))
                        if (index == 0) path.moveTo(last.x, last.y) else path.lineTo(last.x, last.y)
                    }
                    return path to last
                }
                val (down, downEnd) = series(ThroughputSample::download)
                val (up, upEnd) = series(ThroughputSample::upload)
                val area = Path().apply {
                    addPath(down)
                    lineTo(endX, size.height)
                    lineTo(endX - visible.lastIndex * step, size.height)
                    close()
                }
                val fill = Brush.verticalGradient(listOf(download.copy(alpha = .25f), Color.Transparent))
                onDrawBehind {
                    for (fraction in listOf(.2f, .5f, .8f)) {
                        val y = size.height * fraction
                        drawLine(grid, Offset(0f, y), Offset(size.width, y))
                    }
                    if (visible.isNotEmpty()) {
                        drawPath(area, fill)
                        drawPath(down, download, style = Stroke(stroke, cap = StrokeCap.Round))
                        drawPath(up, upload, style = Stroke(stroke, cap = StrokeCap.Round, pathEffect = dash))
                        drawCircle(download, stroke, downEnd)
                        drawCircle(upload, stroke, upEnd)
                    }
                }
            }
    ) {}
}
