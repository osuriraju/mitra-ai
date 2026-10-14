package tech.beyondscale.mitra.feature.habits

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import tech.beyondscale.mitra.designsystem.components.IconBox
import tech.beyondscale.mitra.designsystem.components.SwipeRow
import tech.beyondscale.mitra.designsystem.components.Tone
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.domain.habits.habitDone
import tech.beyondscale.mitra.domain.habits.habitSubline
import tech.beyondscale.mitra.domain.habits.habitValue
import tech.beyondscale.mitra.domain.habits.stepFor
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.HabitType

/** What one habit row shows (web `HRow` in screens/habits.tsx). */
data class HabitRowUi(
    val id: String,
    val name: String,
    /** Web icon id; resolve with [habitIcon]. */
    val icon: String,
    val done: Boolean,
    /** `"5/8"` on the check button of a measurable habit logged part-way today; null otherwise. */
    val partial: String?,
    /** `"5/8 glasses · 8 glasses · daily · 🔥 12-day streak"` or `"… · Recovering — start again today"`. */
    val sub: String,
    val recovering: Boolean,
    /** The check button's action: `"Undo"` · `"Log +1 glasses"` · `"Mark done"`. */
    val checkLabel: String,
)

fun habitRowUi(h: Habit, today: String): HabitRowUi {
    val done = habitDone(h, today)
    val measurable = h.type != HabitType.Binary
    val v = habitValue(h, today)
    val sub = habitSubline(h, today)
    return HabitRowUi(
        id = h.id,
        name = h.name,
        icon = h.icon,
        done = done,
        partial = if (!done && measurable && v > 0) "$v/${h.target}" else null,
        sub = sub.text,
        recovering = sub.recovering,
        checkLabel = when {
            done -> "Undo"
            measurable -> "Log +${stepFor(h)} ${h.unit}"
            else -> "Mark done"
        },
    )
}

/** A habit's icon by its web id; unknown ids fall back to Repeat (MitraIcons.byName's own fallback is Circle). */
fun habitIcon(name: String): ImageVector {
    val icon = MitraIcons.byName(name)
    return if (icon == MitraIcons.Circle && name != "circle") MitraIcons.Repeat else icon
}

private const val RECOVERING = "Recovering — start again today"

/** Tap the row to open the habit; the round button on the right logs today (web `HRow`). */
@Composable
fun HabitRow(row: HabitRowUi, onCheck: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = Mitra.colors
    Row(
        modifier.fillMaxWidth().background(c.surface).heightIn(min = 60.dp).padding(end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "Open habit", onClick = onOpen)
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBox(habitIcon(row.icon), tone = if (row.done) Tone.Success else Tone.Neutral)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.name, style = Mitra.type.body.copy(fontWeight = FontWeight.Medium), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = if (row.recovering && row.sub.endsWith(RECOVERING)) {
                    buildAnnotatedString {
                        append(row.sub.removeSuffix(RECOVERING))
                        withStyle(SpanStyle(color = c.accent)) { append(RECOVERING) }
                    }
                } else {
                    buildAnnotatedString { append(row.sub) }
                }
                Text(sub, style = Mitra.type.xs, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        HabitCheckButton(row, onCheck)
    }
}

/** Web `.check`: filled success when done, the partial count when part-way, else ＋. 48 dp target, 34 dp visible. */
@Composable
private fun HabitCheckButton(row: HabitRowUi, onCheck: () -> Unit) {
    val c = Mitra.colors
    val partial = row.partial
    val (bg, fg, border) = when {
        row.done -> Triple(c.success, c.onAccent, Color.Transparent)
        partial != null -> Triple(c.accentSoft, c.accent, c.accent)
        else -> Triple(Color.Transparent, c.muted, c.border)
    }
    // The partial count ("5/8") is spoken after this from the button's own text.
    val spoken = "${row.checkLabel}, ${row.name}"
    Box(
        Modifier.size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onCheck)
            .semantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(bg).border(1.5.dp, border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when {
                row.done -> Icon(MitraIcons.Check, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                partial != null -> Text(partial, style = Mitra.type.xs.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1)
                else -> Icon(MitraIcons.Plus, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** [HabitRow] with swipe right = complete today (open habits only) and swipe left = archive (history kept, with Undo). */
@Composable
fun SwipeHabitRow(
    row: HabitRowUi,
    onCheck: () -> Unit,
    onComplete: () -> Unit,
    onArchive: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SwipeRow(
        onSwipeRight = if (!row.done) onComplete else null,
        onSwipeLeft = onArchive,
        rightLabel = "Done today",
        rightIcon = MitraIcons.Check,
        leftLabel = "Archive",
        leftIcon = MitraIcons.Archive,
        modifier = modifier,
    ) {
        HabitRow(row, onCheck, onOpen)
    }
}
