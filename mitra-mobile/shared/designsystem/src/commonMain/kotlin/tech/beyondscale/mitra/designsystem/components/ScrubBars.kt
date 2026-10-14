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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

/** Bar index under [x] in a strip of [width] split into [count] equal slots; clamped, or -1 when there is nothing to hit. */
fun scrubIndex(x: Float, width: Float, count: Int): Int {
    if (count <= 0 || width <= 0f) return -1
    return (x / width * count).toInt().coerceIn(0, count - 1)
}

/** Bar chart; press and drag shows a tooltip for the bar under the finger (spec §9). */
@Composable
fun ScrubBars(
    values: List<Long>,
    labels: List<String>,
    highlight: Int = -1,
    format: (Long) -> String,
    modifier: Modifier = Modifier.height(150.dp),
) {
    val c = Mitra.colors
    val haptics = LocalHapticFeedback.current
    var active by remember { mutableIntStateOf(-1) }
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val summary = values.indices.joinToString(", ") { "${labels.getOrElse(it) { "" }} ${format(values[it])}" }
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
            val n = values.size
            if (n == 0) return@Canvas
            val slot = size.width / n
            val barW = slot * 0.62f
            values.forEachIndexed { i, v ->
                val h = if (v <= 0L) 2.dp.toPx() else (v.toFloat() / max * size.height).coerceAtLeast(3.dp.toPx())
                val color = when {
                    i == active || i == highlight -> c.accent
                    else -> c.accent.copy(alpha = 0.45f)
                }
                drawRoundRect(
                    color, Offset(i * slot + (slot - barW) / 2f, size.height - h), Size(barW, h),
                    CornerRadius(3.dp.toPx()),
                )
            }
        }
        if (active in values.indices) {
            val label = labels.getOrElse(active) { "" }
            Text(
                "$label · ${format(values[active])}",
                Modifier.align(Alignment.TopCenter).clearAndSetSemantics { }.background(c.surface2, RoundedCornerShape(MitraDimens.radiusSm)).padding(horizontal = 8.dp, vertical = 4.dp),
                style = Mitra.type.xs, color = c.text,
            )
        }
    }
}
