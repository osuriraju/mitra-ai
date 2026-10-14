package tech.beyondscale.mitra.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt
import tech.beyondscale.mitra.designsystem.theme.Mitra

data class DonutSegment(val value: Long, val color: Color, val label: String)

/** Degrees per value, summing to 360 when the total is positive; empty when there is nothing to show. */
fun donutSweeps(values: List<Long>): List<Float> {
    val total = values.sumOf { it.coerceAtLeast(0L) }
    if (total <= 0L) return emptyList()
    return values.map { it.coerceAtLeast(0L).toDouble().div(total).times(360.0).toFloat() }
}

/** Spoken summary, e.g. "Food 40%, Rent 30%, Other 30%", followed by [centerLabel] when present. */
fun donutSummary(segments: List<DonutSegment>, centerLabel: String? = null): String {
    val total = segments.sumOf { it.value.coerceAtLeast(0L) }
    val parts = if (total <= 0L) emptyList() else segments.map { "${it.label} ${(it.value.coerceAtLeast(0L) * 100.0 / total).roundToInt()}%" }
    return (parts + listOfNotNull(centerLabel)).joinToString(", ")
}

@Composable
fun Donut(segments: List<DonutSegment>, size: Dp, stroke: Dp, centerLabel: String? = null, modifier: Modifier = Modifier) {
    val c = Mitra.colors
    val sweeps = donutSweeps(segments.map { it.value })
    val summary = donutSummary(segments, centerLabel)
    Box(modifier.size(size).semantics(mergeDescendants = true) { if (summary.isNotEmpty()) contentDescription = summary }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = stroke.toPx()
            val d = this.size.minDimension - w
            val tl = Offset(w / 2f, w / 2f)
            val box = Size(d, d)
            drawArc(c.track, 0f, 360f, false, tl, box, style = Stroke(w))
            var start = -90f
            sweeps.forEachIndexed { i, sw ->
                if (sw > 0f) drawArc(segments[i].color, start, sw, false, tl, box, style = Stroke(w, cap = StrokeCap.Butt))
                start += sw
            }
        }
        if (centerLabel != null) {
            Text(centerLabel, Modifier.clearAndSetSemantics { }, style = Mitra.type.num.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        }
    }
}
