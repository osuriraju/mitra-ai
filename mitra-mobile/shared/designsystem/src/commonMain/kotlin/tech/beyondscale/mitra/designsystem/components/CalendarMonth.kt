package tech.beyondscale.mitra.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tech.beyondscale.mitra.designsystem.theme.Mitra

/** One square of a month grid. Leading days of the previous month have no [date] and `inMonth = false`. */
data class CalendarCell(val date: String?, val day: Int, val inMonth: Boolean)

/**
 * How a day renders (web history grid classes `hit` / `miss` / `today` / `out` and the partial dot).
 * [rest] marks a day the habit isn't scheduled; it only changes what is spoken ("rest day").
 */
data class CalendarDayStyle(
    val hit: Boolean,
    val miss: Boolean,
    val today: Boolean,
    val future: Boolean,
    val partial: Boolean,
    val rest: Boolean = false,
)

private val MONTHS = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

private fun isLeap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0

private fun daysIn(y: Int, m: Int): Int = when (m) {
    2 -> if (isLeap(y)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

/** Day of week with Monday = 0 … Sunday = 6 (Sakamoto). */
private fun dowMon(y: Int, m: Int, d: Int): Int {
    val t = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
    val yy = if (m < 3) y - 1 else y
    val sun0 = (yy + yy / 4 - yy / 100 + yy / 400 + t[m - 1] + d) % 7
    return (sun0 + 6) % 7
}

/**
 * Monday-first cells for the month containing [monthFirst] ("YYYY-MM-DD"; the day part is ignored):
 * the trailing days of the previous month as `inMonth = false`, then every day of the month (web history grid).
 */
fun calendarCells(monthFirst: String): List<CalendarCell> {
    val y = monthFirst.substring(0, 4).toInt()
    val m = monthFirst.substring(5, 7).toInt()
    val lead = dowMon(y, m, 1)
    val prevDays = if (m == 1) daysIn(y - 1, 12) else daysIn(y, m - 1)
    val prefix = "${monthFirst.substring(0, 7)}-"
    return List(lead) { i -> CalendarCell(null, prevDays - lead + i + 1, false) } +
        List(daysIn(y, m)) { i -> CalendarCell(prefix + (i + 1).toString().padStart(2, '0'), i + 1, true) }
}

/** "14 October, today, done" — spoken label for an in-month day. */
fun calendarCellDescription(cell: CalendarCell, style: CalendarDayStyle): String {
    val month = cell.date?.substring(5, 7)?.toIntOrNull()?.let { MONTHS.getOrNull(it - 1) }
    val status = when {
        style.hit -> "done"
        style.partial -> "partial"
        style.miss -> "missed"
        style.future -> "upcoming"
        style.rest -> "rest day"
        else -> "not done"
    }
    return listOfNotNull(listOfNotNull(cell.day.toString(), month).joinToString(" "), "today".takeIf { style.today }, status).joinToString(", ")
}

/**
 * Month grid (web habit history `calendar-grid`): weekday header, then [cells] seven to a row.
 * Past and present days are buttons calling [onTap] with their date; future days are not tappable.
 * Cells are square and at least 40 dp tall — a deliberate exception to the 48 dp target, since seven 48 dp
 * columns plus gaps don't fit a narrow phone inside a card (web grid parity).
 */
@Composable
fun CalendarMonth(
    cells: List<CalendarCell>,
    decorate: @Composable (CalendarCell) -> CalendarDayStyle,
    onTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Mitra.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(
                    it, Modifier.weight(1f).padding(vertical = 4.dp), textAlign = TextAlign.Center,
                    style = Mitra.type.eyebrow, color = c.faint,
                )
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { cell -> Box(Modifier.weight(1f)) { Day(cell, decorate, onTap) } }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Day(cell: CalendarCell, decorate: @Composable (CalendarCell) -> CalendarDayStyle, onTap: (String) -> Unit) {
    val c = Mitra.colors
    val shape = RoundedCornerShape(10.dp)
    val base = Modifier.fillMaxWidth().aspectRatio(1f).heightIn(min = 40.dp).clip(shape)
    if (!cell.inMonth || cell.date == null) {
        Box(base.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            Text(cell.day.toString(), style = Mitra.type.num, color = c.faint)
        }
        return
    }
    val date = cell.date
    val s = decorate(cell)
    val bg = when {
        s.hit -> c.successSoft
        s.miss -> c.surface2
        else -> Color.Transparent
    }
    val fg = when {
        s.hit -> c.success
        s.future -> c.faint
        else -> c.text
    }
    val spoken = calendarCellDescription(cell, s)
    Box(
        base.background(bg)
            .then(if (s.today) Modifier.border(2.dp, c.accent, shape) else Modifier)
            .clickable(enabled = !s.future, role = Role.Button, onClickLabel = if (s.hit) "Unmark" else "Mark done") { onTap(date) }
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            cell.day.toString(), Modifier.clearAndSetSemantics { },
            style = Mitra.type.num.copy(fontWeight = if (s.hit) FontWeight.SemiBold else FontWeight.Medium), color = fg,
        )
        if (s.partial && !s.hit) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(4.dp).clip(CircleShape).background(c.accent))
        }
    }
}
