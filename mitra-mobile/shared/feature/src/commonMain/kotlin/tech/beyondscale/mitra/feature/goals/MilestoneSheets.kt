package tech.beyondscale.mitra.feature.goals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.diffDays
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.core.fmtShort
import tech.beyondscale.mitra.core.jsRound
import tech.beyondscale.mitra.core.localeIN
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraConfirmDialog
import tech.beyondscale.mitra.designsystem.components.MitraProgressBar
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.goals.goalValueLabel
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.Milestone
import tech.beyondscale.mitra.feature.common.MitraDatePickerDialog
import tech.beyondscale.mitra.feature.tasks.TaskRow
import tech.beyondscale.mitra.feature.tasks.TaskRowUi
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** The milestone detail sheet over goal detail (web `MilestoneDetailScreen` side panel — deviation e). */
data class MilestoneDetailUi(
    val id: String,
    val title: String,
    val goalName: String,
    /** "Remaining" when the milestone has a target value, else "Status". */
    val kpiLabel: String,
    /** "₹17,000" · "Reached" · "Open" */
    val kpiValue: String,
    /** "14 Jan 2027" · "—" */
    val targetDate: String,
    val pct: Int,
    val reached: Boolean,
    /** "₹58,000 of ₹75,000 · on pace at ₹8,000/month (≈ 3 months)" · "Reached Mon 12 Oct" · "Tick it when the milestone is done." */
    val line: String,
    /** The notes field (a local draft until the debounced save lands). */
    val notes: String,
    /** Open tasks linked to the goal. */
    val tasks: List<TaskRowUi>,
)

/**
 * Web `MilestoneDetailScreen`: remaining = target − current (≥ 0); bar = current ÷ target (100 when reached without a
 * target); the pace is the change between the first and last progress entries per 30 days.
 */
fun milestoneDetailUi(g: Goal, m: Milestone, openTasks: List<TaskRowUi>, notesDraft: String?, today: String): MilestoneDetailUi {
    val target = m.target?.takeIf { it != 0.0 }
    val remaining = target?.let { max(0.0, it - g.current) } ?: 0.0
    val pct = when {
        target != null -> min(100L, jsRound(g.current / target * 100)).toInt()
        m.reachedOn != null -> 100
        else -> 0
    }
    val reachedOn = m.reachedOn
    val line = when {
        reachedOn != null -> "Reached ${fmtDay(reachedOn, today, relative = false)}"
        target != null -> {
            val pace = milestonePace(g)
            "${goalValueLabel(g)} of ${g.unit}${localeIN(target)}" +
                if (pace > 0) " · on pace at ${g.unit}${localeIN(jsRound(pace).toDouble())}/month (≈ ${ceil(remaining / pace).toLong()} months)" else ""
        }
        else -> "Tick it when the milestone is done."
    }
    return MilestoneDetailUi(
        id = m.id,
        title = m.title,
        goalName = g.name,
        kpiLabel = if (target != null) "Remaining" else "Status",
        kpiValue = if (target != null) "${g.unit}${localeIN(remaining)}" else if (reachedOn != null) "Reached" else "Open",
        targetDate = m.targetDate?.let { "${fmtShort(it)} ${it.take(4)}" } ?: "—",
        pct = pct,
        reached = reachedOn != null,
        line = line,
        notes = notesDraft ?: m.notes ?: "",
        tasks = openTasks,
    )
}

/** Progress per 30 days between the first and the last log entry; 0 with fewer than two entries. */
internal fun milestonePace(g: Goal): Double {
    val log = g.progressLog
    if (log.size < 2) return 0.0
    val first = log.first()
    val last = log.last()
    return (last.value - first.value) / max(1, diffDays(last.date, first.date)) * 30
}

/** Add / edit milestone (web `MilestoneForm`): title, reached-at value (numeric goals), target date, notes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MilestoneFormSheet(
    ui: MilestoneFormUi,
    goalName: String,
    onSave: (title: String, target: String, date: String?, notes: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Mitra.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val cancel: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
    var title by rememberSaveable(ui.id) { mutableStateOf(ui.title) }
    var target by rememberSaveable(ui.id) { mutableStateOf(ui.target) }
    var date by rememberSaveable(ui.id) { mutableStateOf(ui.date) }
    var notes by rememberSaveable(ui.id) { mutableStateOf(ui.notes) }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val save = { onSave(title, target, date, notes) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.bg) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = MitraDimens.pad),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (ui.id == null) "Add milestone" else "Edit milestone",
                        style = Mitra.type.h2, color = c.text, modifier = Modifier.semantics { heading() },
                    )
                    Text(goalName, style = Mitra.type.sm, color = c.muted)
                }
                MitraTextField(
                    value = title,
                    onValueChange = { title = it.take(MILESTONE_TITLE_LIMIT) },
                    label = "Milestone",
                    placeholder = "e.g. ₹75,000",
                    imeAction = ImeAction.Done,
                    onImeAction = save,
                    fieldModifier = Modifier.focusRequester(focus),
                )
                if (ui.showTarget) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        MitraTextField(
                            value = target,
                            onValueChange = { target = goalDecimalInput(it) },
                            label = "Reached at value" + if (ui.unit.isNotEmpty()) " (${ui.unit})" else "",
                            keyboardType = KeyboardType.Decimal,
                        )
                        Text("Auto-ticks when progress passes this", style = Mitra.type.xs, color = c.muted)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Target date", style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = c.muted)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MitraButton(
                            date?.let { "${fmtShort(it)} ${it.take(4)}" } ?: "No date",
                            onClick = { pickDate = true },
                            kind = ButtonKind.Outline,
                            icon = MitraIcons.Calendar,
                        )
                        if (date != null) MitraButton("Clear", onClick = { date = null }, kind = ButtonKind.Ghost)
                    }
                }
                MitraTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = "Notes",
                    singleLine = false,
                    minLines = 2,
                    imeAction = ImeAction.Default,
                )
            }
            Row(Modifier.fillMaxWidth().padding(MitraDimens.pad), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MitraButton("Cancel", onClick = cancel, kind = ButtonKind.Outline, modifier = Modifier.weight(1f))
                MitraButton(if (ui.id == null) "Add" else "Save", onClick = save, modifier = Modifier.weight(1f))
            }
        }
    }
    if (pickDate) MitraDatePickerDialog(date, onPick = { date = it }, onDismiss = { pickDate = false })
}

/** Milestone detail (deviation e: a bottom sheet over goal detail). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MilestoneDetailSheet(
    ui: MilestoneDetailUi,
    onAction: (GoalDetailAction) -> Unit,
    onOpenTask: (String) -> Unit,
    onAddTask: () -> Unit,
) {
    val c = Mitra.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var menu by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val close = { onAction(GoalDetailAction.OpenMilestone(null)) }
    ModalBottomSheet(onDismissRequest = close, sheetState = sheetState, containerColor = c.bg) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
            Row(Modifier.padding(start = MitraDimens.pad, end = 4.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Milestone · ${ui.title}", style = Mitra.type.h2, color = c.text, modifier = Modifier.semantics { heading() })
                    Text(ui.goalName, style = Mitra.type.sm, color = c.muted)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(MitraIcons.More, contentDescription = "More milestone actions", tint = c.text) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Delete milestone", style = Mitra.type.body, color = c.danger) },
                            leadingIcon = { Icon(MitraIcons.Trash, contentDescription = null, tint = c.danger) },
                            onClick = { menu = false; confirmDelete = true },
                        )
                    }
                }
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = MitraDimens.pad).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                MitraCard {
                    Row {
                        GoalKpi(ui.kpiLabel, ui.kpiValue, Modifier.weight(1f))
                        GoalKpi("Target date", ui.targetDate, Modifier.weight(1f))
                    }
                    MitraProgressBar(ui.pct / 100f, Modifier.padding(top = 8.dp), color = if (ui.reached) c.success else c.accent)
                    Text(ui.line, style = Mitra.type.xs, color = c.muted, modifier = Modifier.padding(top = 8.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Tasks to reach it", style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = c.muted)
                    MitraCard(contentPadding = PaddingValues(0.dp)) {
                        if (ui.tasks.isEmpty()) {
                            Text("No open tasks linked to this goal.", style = Mitra.type.sm, color = c.muted, modifier = Modifier.padding(14.dp))
                        }
                        ui.tasks.forEachIndexed { i, r ->
                            if (i > 0) HorizontalDivider(color = c.border, thickness = 1.dp, modifier = Modifier.padding(start = 48.dp))
                            TaskRow(r, onToggle = { onAction(GoalDetailAction.ToggleTask(r.id)) }, onOpen = { onOpenTask(r.id) })
                        }
                    }
                    MitraButton("Add task", onClick = onAddTask, kind = ButtonKind.Ghost, icon = MitraIcons.Plus)
                }
                MitraTextField(
                    value = ui.notes,
                    onValueChange = { onAction(GoalDetailAction.MilestoneNotes(ui.id, it)) },
                    label = "Notes",
                    placeholder = "Anything that affects this milestone",
                    singleLine = false,
                    minLines = 2,
                    imeAction = ImeAction.Default,
                )
            }
            Row(Modifier.fillMaxWidth().padding(MitraDimens.pad), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MitraButton(
                    "Edit", onClick = { onAction(GoalDetailAction.EditMilestone(ui.id)) }, kind = ButtonKind.Outline,
                    icon = MitraIcons.Edit, modifier = Modifier.weight(1f),
                )
                MitraButton(
                    if (ui.reached) "Mark not reached" else "Mark reached",
                    onClick = { onAction(GoalDetailAction.TickMilestone(ui.id, fromSheet = true)) },
                    icon = if (ui.reached) MitraIcons.Undo else MitraIcons.Check,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    if (confirmDelete) {
        MitraConfirmDialog(
            title = "Delete “${ui.title}”?",
            body = "The milestone is removed from this goal.",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; onAction(GoalDetailAction.DeleteMilestone(ui.id)) },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
internal fun GoalKpi(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = Mitra.type.xs, color = Mitra.colors.muted)
        Text(value, style = Mitra.type.h3, color = Mitra.colors.text)
    }
}

/** A text link with a 48 dp touch target. */
@Composable
internal fun GoalTextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold),
        color = Mitra.colors.accent,
        modifier = modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp, vertical = 14.dp),
    )
}

/** Server cap on milestone titles (mitra-backend/src/goals/schemas.ts); longer titles are rejected and vanish on refresh. */
private const val MILESTONE_TITLE_LIMIT = 120
