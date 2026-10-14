package tech.beyondscale.mitra.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import tech.beyondscale.mitra.designsystem.theme.Mitra

private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** "Mon done, Tue missed, Wed done (today), Thu upcoming, …" — one entry per day, Monday first; days after today aren't missed yet. */
fun weekDotsDescription(days: List<Boolean>, todayIndex: Int): String =
    days.mapIndexed { i, on ->
        val status = when {
            on -> "done"
            i > todayIndex -> "upcoming"
            else -> "missed"
        }
        "${WEEKDAYS.getOrElse(i) { "Day ${i + 1}" }} $status${if (i == todayIndex) " (today)" else ""}"
    }.joinToString(", ")

/** Monday-first week strip (web `WeekDots`): done days filled success, today outlined in accent. */
@Composable
fun WeekDots(days: List<Boolean>, todayIndex: Int, modifier: Modifier = Modifier) {
    val c = Mitra.colors
    val shape = RoundedCornerShape(4.dp)
    val spoken = weekDotsDescription(days, todayIndex)
    // Padding leaves room for today's outline, which is drawn outside the dot (web `outline-offset`) so it never shifts the row.
    Row(modifier.padding(3.dp).clearAndSetSemantics { contentDescription = spoken }, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        days.forEachIndexed { i, on ->
            Box(
                Modifier.size(16.dp)
                    .then(
                        if (i == todayIndex) {
                            Modifier.drawBehind {
                                // Web: 2px outline, 1px offset → stroke centred 2 dp outside the 16 dp dot.
                                val out = 2.dp.toPx()
                                drawRoundRect(
                                    c.accent, Offset(-out, -out), Size(size.width + out * 2, size.height + out * 2),
                                    CornerRadius(4.dp.toPx() + out), style = Stroke(2.dp.toPx()),
                                )
                            }
                        } else Modifier,
                    )
                    .background(if (on) c.success else c.track, shape),
            )
        }
    }
}
