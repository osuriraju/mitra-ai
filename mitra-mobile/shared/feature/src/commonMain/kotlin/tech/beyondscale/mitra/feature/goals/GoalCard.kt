package tech.beyondscale.mitra.feature.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import tech.beyondscale.mitra.designsystem.components.ChipTone
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.Ring
import tech.beyondscale.mitra.designsystem.components.Tone
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.domain.goals.StatusTone
import tech.beyondscale.mitra.domain.goals.cardRingTone
import tech.beyondscale.mitra.domain.goals.dueMonthLabel
import tech.beyondscale.mitra.domain.goals.goalProgress
import tech.beyondscale.mitra.domain.goals.goalStatus
import tech.beyondscale.mitra.domain.goals.linksLine
import tech.beyondscale.mitra.domain.goals.reviewDue
import tech.beyondscale.mitra.domain.goals.statusTone
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task

/** One card of the goals list (web `GoalCard` in screens/goals.tsx). */
data class GoalCardUi(
    val id: String,
    val name: String,
    val pct: Int,
    val ringTone: StatusTone,
    val status: String,
    val statusTone: StatusTone,
    /** "Numeric · Medium-term · due Mar 2027" */
    val meta: String,
    /** The goal's next action, or null when none is set. */
    val next: String?,
    /** "1 project · 3 tasks · 2 habits" / "Nothing linked yet", plus " · review due". */
    val links: String,
    /** Paused goals render at 70 % opacity. */
    val dimmed: Boolean,
)

fun goalCardUi(g: Goal, habits: List<Habit>, tasks: List<Task>, projects: List<Project>, today: String): GoalCardUi {
    val st = goalStatus(g, habits, today)
    return GoalCardUi(
        id = g.id,
        name = g.name,
        pct = goalProgress(g, habits, today),
        ringTone = cardRingTone(st),
        status = st.label,
        statusTone = statusTone(st),
        meta = "${g.type.label} · ${g.term.wire} · due ${dueMonthLabel(g)}",
        next = g.nextAction.ifEmpty { null },
        links = linksLine(g.id, tasks, habits, projects) + if (reviewDue(g, today)) " · review due" else "",
        dimmed = g.state == GoalState.Paused,
    )
}

/** The design-system tone for a goal [StatusTone] (rings, bars). */
fun StatusTone.tone(): Tone = when (this) {
    StatusTone.Success -> Tone.Success
    StatusTone.Warning -> Tone.Warning
    StatusTone.Danger -> Tone.Danger
    StatusTone.Accent -> Tone.Accent
    StatusTone.Neutral -> Tone.Neutral
}

/** The chip tone for a goal [StatusTone] (status chips). */
fun StatusTone.chipTone(): ChipTone = when (this) {
    StatusTone.Success -> ChipTone.Success
    StatusTone.Warning -> ChipTone.Warning
    StatusTone.Danger -> ChipTone.Danger
    StatusTone.Accent -> ChipTone.Accent
    StatusTone.Neutral -> ChipTone.Neutral
}

@Composable
fun GoalCard(ui: GoalCardUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Mitra.colors
    MitraCard(
        modifier.fillMaxWidth().alpha(if (ui.dimmed) 0.7f else 1f),
        contentPadding = PaddingValues(14.dp),
        onClick = onClick,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            Ring(ui.pct, size = 56.dp, stroke = 6.dp, label = "${ui.pct}%", tone = ui.ringTone.tone())
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ui.name, style = Mitra.type.bodyBold, color = c.text, modifier = Modifier.weight(1f))
                    MitraChip(ui.status, tone = ui.statusTone.chipTone())
                }
                Text(ui.meta, style = Mitra.type.sm, color = c.muted)
                ui.next?.let { next ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(MitraIcons.ArrowRight, contentDescription = null, tint = c.muted, modifier = Modifier.size(14.dp))
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Next: ") }
                                append(next)
                            },
                            style = Mitra.type.sm,
                            color = c.text,
                        )
                    }
                }
                Text(ui.links, style = Mitra.type.xs, color = c.faint)
            }
        }
    }
}
