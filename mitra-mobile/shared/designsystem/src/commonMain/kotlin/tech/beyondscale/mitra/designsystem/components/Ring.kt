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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import tech.beyondscale.mitra.designsystem.theme.Mitra

/** Semantic colour for rings, icon boxes and other small marks (web `tone` class). */
enum class Tone { Accent, Success, Warning, Danger, Neutral }

@Composable
fun Tone.color(): Color = when (this) {
    Tone.Accent -> Mitra.colors.accent
    Tone.Success -> Mitra.colors.success
    Tone.Warning -> Mitra.colors.warning
    Tone.Danger -> Mitra.colors.danger
    Tone.Neutral -> Mitra.colors.muted
}

/** Sweep in degrees for a percentage, clamped to 0..360. */
fun ringSweep(pct: Int): Float = pct.coerceIn(0, 100) * 3.6f

/**
 * Progress ring (web `Ring`): track circle plus a round-capped arc from 12 o'clock.
 * Spoken as "<label> progress" (callers pass the visible text, e.g. "45%"), or "<pct>% progress" without a label.
 */
@Composable
fun Ring(
    pct: Int,
    size: Dp = 44.dp,
    stroke: Dp = 5.dp,
    label: String? = null,
    tone: Tone = Tone.Accent,
    modifier: Modifier = Modifier,
) {
    val c = Mitra.colors
    val col = tone.color()
    val sweep = ringSweep(pct)
    val spoken = if (label.isNullOrEmpty()) "${pct.coerceIn(0, 100)}% progress" else "$label progress"
    Box(modifier.size(size).clearAndSetSemantics { contentDescription = spoken }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = stroke.toPx()
            val d = this.size.minDimension - w
            val tl = Offset(w / 2f, w / 2f)
            val box = Size(d, d)
            drawArc(c.track, 0f, 360f, false, tl, box, style = Stroke(w))
            if (sweep > 0f) drawArc(col, -90f, sweep, false, tl, box, style = Stroke(w, cap = StrokeCap.Round))
        }
        if (!label.isNullOrEmpty()) {
            // Web: font-size = round(size / 4).
            Text(
                label,
                style = Mitra.type.num.copy(fontWeight = FontWeight.SemiBold, fontSize = (size.value / 4f).roundToInt().sp),
                color = c.text, maxLines = 1,
            )
        }
    }
}
