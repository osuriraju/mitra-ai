package tech.beyondscale.mitra.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import tech.beyondscale.mitra.designsystem.theme.Mitra

private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** "Mon done, Tue missed, Wed done (today), …" — one entry per day, Monday first. */
fun weekDotsDescription(days: List<Boolean>, todayIndex: Int): String =
    days.mapIndexed { i, on ->
        "${WEEKDAYS.getOrElse(i) { "Day ${i + 1}" }} ${if (on) "done" else "missed"}${if (i == todayIndex) " (today)" else ""}"
    }.joinToString(", ")

/** Monday-first week strip (web `WeekDots`): done days filled success, today outlined in accent. */
@Composable
fun WeekDots(days: List<Boolean>, todayIndex: Int, modifier: Modifier = Modifier) {
    val c = Mitra.colors
    val shape = RoundedCornerShape(4.dp)
    val outline = RoundedCornerShape(6.dp)
    val spoken = weekDotsDescription(days, todayIndex)
    Row(modifier.clearAndSetSemantics { contentDescription = spoken }, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        days.forEachIndexed { i, on ->
            // 2dp outline with 1dp offset (web `outline-offset: 1px`) without shifting neighbours.
            Box(
                Modifier.size(20.dp)
                    .then(if (i == todayIndex) Modifier.border(2.dp, c.accent, outline) else Modifier)
                    .padding(2.dp),
            ) {
                Box(Modifier.size(16.dp).background(if (on) c.success else c.track, shape))
            }
        }
    }
}
