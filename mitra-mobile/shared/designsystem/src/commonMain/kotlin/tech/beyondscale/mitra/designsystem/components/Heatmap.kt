package tech.beyondscale.mitra.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tech.beyondscale.mitra.designsystem.theme.Mitra

/** Accent mix for a heat level (web `.heat i.l1…l4`: 30 / 55 / 80 / 100 % accent over the surface); 0 = track. */
fun heatFraction(level: Int): Float = when {
    level <= 0 -> 0f
    level == 1 -> 0.30f
    level == 2 -> 0.55f
    level == 3 -> 0.80f
    else -> 1f
}

/**
 * Square-cell heatmap (web `Heat`): [cells] are levels 0..4 laid out row-major in [columns] columns.
 * Decorative detail for screen readers: [description] (e.g. "12 weeks, 48 of 84 days done") is the spoken summary.
 */
@Composable
fun Heatmap(cells: List<Int>, columns: Int = 7, modifier: Modifier = Modifier, description: String? = null) {
    val c = Mitra.colors
    val cols = columns.coerceAtLeast(1)
    val rows = (cells.size + cols - 1) / cols
    val gap = 3.dp
    // Height follows the width so the cells stay square.
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cell = (maxWidth - gap * (cols - 1)) / cols
        val height = if (rows == 0) 0.dp else cell * rows + gap * (rows - 1)
        Canvas(
            Modifier.fillMaxWidth().height(height).semantics { if (description != null) contentDescription = description },
        ) {
            val g = gap.toPx()
            val s = (size.width - g * (cols - 1)) / cols
            val r = CornerRadius(3.dp.toPx())
            cells.forEachIndexed { i, level ->
                val color = if (level <= 0) c.track else lerp(c.surface, c.accent, heatFraction(level))
                drawRoundRect(color, Offset((i % cols) * (s + g), (i / cols) * (s + g)), Size(s, s), r)
            }
        }
    }
}
