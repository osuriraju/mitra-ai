package tech.beyondscale.mitra.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

/**
 * Top of the y scale, as the web computes it: the largest of [values] and [goal] plus 8 % headroom
 * (`Math.max(...vals, goal) * 1.08 || 1`), so 1 when that is 0 or there is nothing to plot.
 */
fun chartMax(values: List<Double>, goal: Double?): Double {
    val all = if (goal != null) values + goal else values
    val top = (all.maxOrNull() ?: 0.0) * 1.08
    return if (top == 0.0 || top.isNaN()) 1.0 else top
}

/** Bottom of the y scale, as the web computes it: the lowest of [values] × 0.9; the goal line is not considered. 0 when empty. */
fun chartMin(values: List<Double>): Double = (values.minOrNull() ?: 0.0) * 0.9

/** Pixel y for [v] on a [min]..[max] scale inside [height] (web `Y`; a zero range divides by 1). */
fun chartY(v: Double, min: Double, max: Double, height: Float): Float {
    val range = (max - min).takeIf { it != 0.0 } ?: 1.0
    return (height - (v - min) / range * height).toFloat()
}

/**
 * Pixel positions for [values] on a [min]..[max] scale inside [width]×[height]: x spreads points edge to edge
 * (web `n = max(2, len)`, so a lone point sits at the left), y via [chartY].
 */
fun chartPoints(values: List<Double>, min: Double, max: Double, width: Float, height: Float): List<Pair<Float, Float>> {
    val n = maxOf(2, values.size)
    return values.mapIndexed { i, v -> i * (width / (n - 1)) to chartY(v, min, max, height) }
}

/**
 * Line chart with area fill, the latest point marked and labelled, and an optional dashed goal line (web `LineChart`).
 * Press and hold, then drag, to read any point (same gesture as [ScrubBars]). The y scale is [chartMin]..[chartMax], as on the web.
 */
@Composable
fun LineChart(
    values: List<Double>,
    labels: List<String>,
    goal: Double? = null,
    format: (Double) -> String,
    modifier: Modifier = Modifier.height(130.dp),
) {
    val c = Mitra.colors
    val haptics = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer()
    val axisStyle = Mitra.type.xs.copy(fontSize = 10.5.sp, color = c.muted)
    val valueStyle = Mitra.type.xs.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = c.text)
    var active by remember { mutableIntStateOf(-1) }
    val max = chartMax(values, goal)
    val min = chartMin(values)
    val summary = buildList {
        values.forEachIndexed { i, v -> add(listOf(labels.getOrElse(i) { "" }, format(v)).filter { it.isNotEmpty() }.joinToString(" ")) }
        if (goal != null) add("goal ${format(goal)}")
    }.joinToString(", ")
    Box(
        modifier.fillMaxWidth()
            .semantics { contentDescription = summary }
            // Scrub only after a long press so vertical drags still scroll the page.
            .pointerInput(values.size) {
                fun move(x: Float) {
                    val i = scrubIndex(x, size.width.toFloat(), values.size)
                    if (i != active) { active = i; if (i >= 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                }
                detectDragGesturesAfterLongPress(
                    onDragStart = { move(it.x) },
                    onDrag = { change, _ -> change.consume(); move(change.position.x) },
                    onDragEnd = { active = -1 },
                    onDragCancel = { active = -1 },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val labelBand = 22.dp.toPx() // web: plot height = h − 22
            val inset = 5.dp.toPx() // keeps the end dots' stroke inside the canvas
            val w = size.width - inset * 2
            val ph = (size.height - labelBand).coerceAtLeast(1f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
            listOf(0f, 0.5f, 1f).forEach { g ->
                val y = ph - ph * g
                drawLine(c.border, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = dash)
            }
            if (goal != null) {
                // A goal below the series' floor would land in the label band; pin it to the plot edges instead.
                val gy = chartY(goal, min, max, ph).coerceIn(0f, ph)
                drawLine(
                    c.success, Offset(0f, gy), Offset(size.width, gy), 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                )
            }
            val pts = chartPoints(values, min, max, w, ph).map { (x, y) -> Offset(x + inset, y) }
            if (pts.isNotEmpty()) {
                val line = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                val area = Path().apply { addPath(line); lineTo(pts.last().x, ph); lineTo(pts.first().x, ph); close() }
                drawPath(area, c.accent.copy(alpha = 0.12f))
                drawPath(line, c.accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val mark = if (active in pts.indices) active else pts.lastIndex
                if (active in pts.indices) {
                    drawLine(c.border, Offset(pts[mark].x, 0f), Offset(pts[mark].x, ph), 1.dp.toPx())
                }
                drawCircle(c.surface, 4.dp.toPx(), pts[mark])
                drawCircle(c.accent, 4.dp.toPx(), pts[mark], style = Stroke(2.dp.toPx()))
                if (active !in pts.indices) {
                    // Latest value above the last point, right-aligned to it (web `textAnchor="end"`).
                    val t = measurer.measure(format(values.last()), valueStyle)
                    val x = (pts.last().x - t.size.width).coerceAtLeast(0f)
                    val y = (pts.last().y - 9.dp.toPx() - t.size.height).coerceAtLeast(0f)
                    drawText(t, topLeft = Offset(x, y))
                }
            }
            val n = maxOf(2, values.size)
            labels.forEachIndexed { i, l ->
                if (l.isEmpty() || i >= n) return@forEachIndexed
                val t = measurer.measure(l, axisStyle)
                val cx = inset + i * (w / (n - 1))
                val x = when (i) { 0 -> 0f; n - 1 -> size.width - t.size.width; else -> cx - t.size.width / 2f }
                drawText(t, topLeft = Offset(x.coerceIn(0f, (size.width - t.size.width).coerceAtLeast(0f)), size.height - t.size.height - 2.dp.toPx()))
            }
        }
        if (active in values.indices) {
            val label = labels.getOrElse(active) { "" }
            Text(
                listOf(label, format(values[active])).filter { it.isNotEmpty() }.joinToString(" · "),
                Modifier.align(Alignment.TopCenter).clearAndSetSemantics { }.background(c.surface2, RoundedCornerShape(MitraDimens.radiusSm)).padding(horizontal = 8.dp, vertical = 4.dp),
                style = Mitra.type.xs, color = c.text,
            )
        }
    }
}
