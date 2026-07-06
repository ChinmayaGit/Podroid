package com.excp.podroid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

data class ChartSeries(
    val samples: List<Float>,
    val color: Color,
)

@Composable
fun LiveLineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    yMin: Float = 0f,
    yMax: Float? = null,
    heightDp: Int = 120,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val resolvedMax = remember(series, yMax) {
        val dataMax = series.flatMap { it.samples }.maxOrNull() ?: 0f
        when {
            yMax != null -> yMax
            dataMax <= 0f -> 1f
            else -> dataMax * 1.15f
        }
    }
    val range = (resolvedMax - yMin).coerceAtLeast(0.001f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp),
    ) {
        val w = size.width
        val h = size.height
        val padL = 4f
        val padR = 4f
        val padT = 4f
        val padB = 4f
        val chartH = h - padT - padB

        for (fraction in listOf(0.25f, 0.5f, 0.75f)) {
            val y = padT + chartH * (1f - fraction)
            drawLine(
                color = gridColor,
                start = Offset(padL, y),
                end = Offset(w - padR, y),
                strokeWidth = 1f,
            )
        }

        series.forEach { s ->
            if (s.samples.size < 2) return@forEach
            val stepX = (w - padL - padR) / (s.samples.size - 1).coerceAtLeast(1)
            val points = s.samples.mapIndexed { i, v ->
                val x = padL + i * stepX
                val norm = ((v.coerceIn(yMin, resolvedMax) - yMin) / range).coerceIn(0f, 1f)
                val y = padT + chartH * (1f - norm)
                Offset(x, y)
            }

            val fillPath = Path().apply {
                moveTo(points.first().x, padT + chartH)
                points.forEach { lineTo(it.x, it.y) }
                lineTo(points.last().x, padT + chartH)
                close()
            }
            drawPath(fillPath, s.color.copy(alpha = 0.12f))

            for (i in 0 until points.lastIndex) {
                drawLine(
                    color = s.color,
                    start = points[i],
                    end = points[i + 1],
                    strokeWidth = 2.5f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
