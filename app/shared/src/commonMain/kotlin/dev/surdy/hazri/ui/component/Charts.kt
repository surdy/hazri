package dev.surdy.hazri.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import dev.surdy.hazri.domain.SignalSample
import dev.surdy.hazri.ui.theme.HazriColors

/**
 * The ten-second sparkline on a Live card.
 *
 * Drawn from raw samples rather than the smoothed value on purpose: the smoothed number is
 * already the big figure beside it, and what the sparkline is for is seeing how much the
 * raw signal is moving underneath it.
 */
@Composable
fun Sparkline(
    values: List<Int>,
    modifier: Modifier = Modifier,
    color: Color = HazriColors.accent,
    points: Int = 10,
) {
    Canvas(modifier = modifier) {
        val sampled = resample(values, points)
        if (sampled.size < 2) return@Canvas
        drawSeries(sampled, color, strokeWidth = 2.dp.toPx(), inset = 2.dp.toPx())
    }
}

/**
 * The sixty-second history chart on Node detail.
 *
 * Grid lines at -50, -70 and -90 dBm and a dot on the newest reading. The axis is fixed
 * rather than fitted to the data: a chart that rescales itself makes a node that has gone
 * quiet look identical to one that has not, and the whole job here is comparing nodes.
 */
@Composable
fun HistoryChart(
    samples: List<SignalSample>,
    modifier: Modifier = Modifier,
    color: Color = HazriColors.accent,
    top: Float = CHART_TOP_DBM,
    bottom: Float = CHART_BOTTOM_DBM,
) {
    Canvas(modifier = modifier) {
        val height = size.height
        val width = size.width

        listOf(-50f, -70f, -90f).forEach { level ->
            val y = height * (top - level) / (top - bottom)
            drawLine(
                color = HazriColors.border,
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = 1.dp.toPx(),
            )
        }

        if (samples.size < 2) return@Canvas

        val first = samples.first().timestamp
        val span = (samples.last().timestamp - first).coerceAtLeast(1L)
        val path = Path()
        samples.forEachIndexed { index, sample ->
            val x = width * (sample.timestamp - first) / span
            val y = height * (top - sample.rssi) / (top - bottom)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        val last = samples.last()
        val lastY = height * (top - last.rssi) / (top - bottom)
        drawCircle(color = HazriColors.surface, radius = 6.dp.toPx(), center = Offset(width, lastY))
        drawCircle(color = color, radius = 4.dp.toPx(), center = Offset(width, lastY))
    }
}

/**
 * The heat colour for a mean RSSI, on the same -95 to -50 dBm ramp as the Coverage legend.
 *
 * Five discrete steps rather than a continuous gradient. Discrete is what makes two cells
 * comparable at a glance across a six-row grid — a continuous ramp reads as one wash.
 */
fun heatColor(meanDbm: Double): Color {
    val fraction = ((meanDbm - HEAT_FLOOR) / (HEAT_CEILING - HEAT_FLOOR)).coerceIn(0.0, 1.0)
    val index = (fraction * (HazriColors.heat.size - 1)).toInt().coerceIn(0, HazriColors.heat.lastIndex)
    return HazriColors.heat[index]
}

/** Text colour that stays legible on [heatColor]'s darkest two steps. */
fun heatTextColor(meanDbm: Double): Color =
    if (meanDbm <= HEAT_FLOOR + HEAT_TEXT_MARGIN) HazriColors.textSecondary else HazriColors.text

/** Top of the Node detail chart's fixed axis, in dBm. */
const val CHART_TOP_DBM = -40f

/** Bottom of the Node detail chart's fixed axis, in dBm. */
const val CHART_BOTTOM_DBM = -100f

private const val HEAT_FLOOR = -95.0
private const val HEAT_CEILING = -50.0
private const val HEAT_TEXT_MARGIN = 8.0

private fun DrawScope.drawSeries(values: List<Int>, color: Color, strokeWidth: Float, inset: Float) {
    val high = values.max().toFloat()
    val low = values.min().toFloat()
    val span = (high - low).coerceAtLeast(1f)
    val usable = size.height - inset * 2
    val step = size.width / (values.size - 1)

    val path = Path()
    values.forEachIndexed { index, value ->
        val x = step * index
        val y = inset + usable * (high - value) / span
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** Takes [count] evenly spaced values out of [values], keeping the newest. */
private fun resample(values: List<Int>, count: Int): List<Int> {
    if (values.size <= count) return values
    val step = (values.size - 1).toDouble() / (count - 1)
    return (0 until count).map { index -> values[(index * step).toInt().coerceAtMost(values.lastIndex)] }
}
