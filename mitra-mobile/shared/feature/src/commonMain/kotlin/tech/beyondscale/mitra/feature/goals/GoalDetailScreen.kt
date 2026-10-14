package tech.beyondscale.mitra.feature.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import tech.beyondscale.mitra.core.jsNum
import tech.beyondscale.mitra.core.localeIN
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.CardTone
import tech.beyondscale.mitra.designsystem.components.EmojiBox
import tech.beyondscale.mitra.designsystem.components.EmptyState
import tech.beyondscale.mitra.designsystem.components.Eyebrow
import tech.beyondscale.mitra.designsystem.components.IconBox
import tech.beyondscale.mitra.designsystem.components.LineChart
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.MitraConfirmDialog
import tech.beyondscale.mitra.designsystem.components.MitraProgressBar
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.components.SectionHeader
import tech.beyondscale.mitra.designsystem.components.Tone
import tech.beyondscale.mitra.designsystem.components.color
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.model.GoalState
import tech.beyondscale.mitra.feature.common.EventEffect
import tech.beyondscale.mitra.feature.tasks.TaskRow

@Composable
fun GoalDetailRoute(
    id: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenHabit: (String) -> Unit,
    onAddTask: (goalId: String) -> Unit,
    vm: GoalDetailViewModel = koinViewModel { parametersOf(id) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Deleting the goal sends "Goal deleted" then Close: go back.
    EventEffect(vm.events, vm::onAction, onClose = onBack)
    // Save a pending formula / milestone notes draft when the app goes to the background or the screen leaves.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.flushNow() }
    DisposableEffect(vm) { onDispose { vm.flushNow() } }
    GoalDetailScreen(
        state, vm::onAction, onBack,
        onEdit = { onEdit(id) },
        onOpenTask = onOpenTask, onOpenProject = onOpenProject, onOpenHabit = onOpenHabit,
        onAddTask = { onAddTask(id) },
    )
}

@Composable
fun GoalDetailScreen(
    state: GoalDetailUiState,
    onAction: (GoalDetailAction) -> Unit,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenHabit: (String) -> Unit,
    onAddTask: () -> Unit,
) {
    val c = Mitra.colors
    var menu by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val found = state.loaded && !state.notFound

    Column(Modifier.fillMaxSize().imePadding()) {
        MitraTopBar(if (found) state.name else "Goal", onBack = onBack) {
            if (found) {
                IconButton(onClick = onEdit) { Icon(MitraIcons.Edit, contentDescription = "Edit goal", tint = c.text) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(MitraIcons.More, contentDescription = "More actions", tint = c.text) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val paused = state.state == GoalState.Paused
                        MenuItem("Log progress", MitraIcons.Plus) { menu = false; onAction(GoalDetailAction.OpenLog) }
                        MenuItem("Add milestone", MitraIcons.Flag) { menu = false; onAction(GoalDetailAction.EditMilestone(null)) }
                        MenuItem("Link a task", MitraIcons.CheckSquare) { menu = false; onAddTask() }
                        MenuItem(if (paused) "Resume goal" else "Pause goal", if (paused) MitraIcons.Play else MitraIcons.Pause) {
                            menu = false; onAction(GoalDetailAction.TogglePause)
                        }
                        MenuItem("Mark completed", MitraIcons.CheckCircle) { menu = false; onAction(GoalDetailAction.Complete) }
                        MenuItem("Delete goal", MitraIcons.Trash, danger = true) { menu = false; confirmDelete = true }
                    }
                }
            }
        }
        if (!state.loaded) return@Column
        if (state.notFound) {
            EmptyState(MitraIcons.Target, "This goal doesn't exist", "It may have been deleted.") {
                MitraButton("All goals", onClick = onBack, kind = ButtonKind.Outline)
            }
            return@Column
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = MitraDimens.pad).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
        ) {
            Eyebrow(state.eyebrow)
            if (state.state != GoalState.Active) StateBanner(state.state) { onAction(GoalDetailAction.Reopen) }
            ProgressCard(state, onAction)
            Milestones(state, onAction)
            ProgressChart(state)
            NextAction(state, onAction, onOpenTask, onAddTask)
            Linked(state, onOpenTask, onOpenProject, onOpenHabit)
            if (state.blockers.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("Blockers")
                    MitraCard(tone = CardTone.Soft) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                            Icon(MitraIcons.Alert, contentDescription = null, tint = c.warning, modifier = Modifier.size(16.dp))
                            Text(state.blockers, style = Mitra.type.sm, color = c.text)
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().background(c.bg).padding(horizontal = MitraDimens.pad, vertical = 10.dp)) {
            MitraButton("Log progress", onClick = { onAction(GoalDetailAction.OpenLog) }, icon = MitraIcons.Plus, modifier = Modifier.fillMaxWidth())
        }
    }

    if (!found) return
    state.log?.takeIf { state.logOpen }?.let { log ->
        LogProgressSheet(log, onSave = { v, n -> onAction(GoalDetailAction.LogProgress(v, n)) }, onDismiss = { onAction(GoalDetailAction.CloseLog) })
    }
    state.milestoneDetail?.let { ui -> MilestoneDetailSheet(ui, onAction, onOpenTask, onAddTask) }
    state.milestoneForm?.let { form ->
        MilestoneFormSheet(
            form, state.name,
            onSave = { title, target, date, notes -> onAction(GoalDetailAction.SaveMilestone(form.id, title, target, date, notes)) },
            onDismiss = { onAction(GoalDetailAction.CloseMilestoneForm) },
        )
    }
    if (confirmDelete) {
        MitraConfirmDialog(
            title = "Delete “${state.name}”?",
            body = "Linked tasks and habits are kept; only the goal is removed.",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; onAction(GoalDetailAction.Delete) },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun StateBanner(s: GoalState, onReopen: () -> Unit) {
    val c = Mitra.colors
    val completed = s == GoalState.Completed
    val fg = if (completed) c.success else c.accent
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MitraDimens.radiusSm)).background(if (completed) c.successSoft else c.accentSoft).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(if (completed) MitraIcons.CheckCircle else MitraIcons.Pause, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(
            if (completed) "Completed — well done." else "Paused — hidden from Today, history kept.",
            style = Mitra.type.sm, color = fg, modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
        GoalTextLink(if (completed) "Reopen" else "Resume", onReopen)
    }
}

@Composable
private fun ProgressCard(state: GoalDetailUiState, onAction: (GoalDetailAction) -> Unit) {
    val c = Mitra.colors
    var editing by rememberSaveable { mutableStateOf(false) }
    MitraCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Progress", style = Mitra.type.xs, color = c.muted)
                Text(
                    buildAnnotatedString {
                        append(state.kpiValue)
                        withStyle(SpanStyle(color = c.muted, fontSize = 14.sp, fontWeight = FontWeight.Medium)) { append(" ${state.kpiSub}") }
                    },
                    style = Mitra.type.h2, color = c.text,
                )
            }
            MitraChip(state.status, tone = state.statusTone.chipTone(), icon = if (state.onTrack) MitraIcons.Check else MitraIcons.Alert)
        }
        MitraProgressBar(state.pct / 100f, Modifier.padding(top = 10.dp), color = state.barTone.tone().color(), height = 10.dp)
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(state.startedLine, style = Mitra.type.xs, color = c.muted)
            Text(state.dueLine, style = Mitra.type.xs, color = c.muted)
        }
        MitraCard(Modifier.padding(top = 12.dp), tone = CardTone.Soft, contentPadding = PaddingValues(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            if (editing) {
                Text("How this is calculated:", style = Mitra.type.sm.copy(fontWeight = FontWeight.Bold), color = c.text)
                MitraTextField(
                    value = state.formulaField,
                    onValueChange = { onAction(GoalDetailAction.Formula(it)) },
                    label = "Formula",
                    placeholder = "Describe how progress is measured",
                    singleLine = false,
                    minLines = 2,
                    imeAction = ImeAction.Default,
                    modifier = Modifier.padding(top = 6.dp, end = 8.dp),
                )
            } else {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("How this is calculated: ") }
                        append(state.formulaText)
                    },
                    style = Mitra.type.sm, color = c.text, modifier = Modifier.padding(end = 8.dp),
                )
            }
            GoalTextLink(if (editing) "Done" else "Edit formula", { editing = !editing })
        }
    }
}

@Composable
private fun Milestones(state: GoalDetailUiState, onAction: (GoalDetailAction) -> Unit) {
    val c = Mitra.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Milestones")
        MitraCard(contentPadding = PaddingValues(0.dp)) {
            if (state.milestones.isEmpty()) {
                Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("No milestones yet.", style = Mitra.type.sm, color = c.muted)
                    GoalTextLink("Add one", { onAction(GoalDetailAction.EditMilestone(null)) })
                }
            }
            state.milestones.forEachIndexed { i, m ->
                if (i > 0) HorizontalDivider(color = c.border, thickness = 1.dp)
                MilestoneRow(m, onOpen = { onAction(GoalDetailAction.OpenMilestone(m.id)) }, onTick = { onAction(GoalDetailAction.TickMilestone(m.id)) })
            }
            if (state.milestones.isNotEmpty()) HorizontalDivider(color = c.border, thickness = 1.dp)
            MitraButton(
                "Add milestone", onClick = { onAction(GoalDetailAction.EditMilestone(null)) }, kind = ButtonKind.Ghost, icon = MitraIcons.Plus,
                modifier = Modifier.padding(6.dp),
            )
        }
    }
}

@Composable
private fun MilestoneRow(m: MilestoneRowUi, onOpen: () -> Unit, onTick: () -> Unit) {
    val c = Mitra.colors
    val dot = when {
        m.reached -> c.success
        m.now -> c.accent
        else -> c.track
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClickLabel = "Open milestone", onClick = onOpen)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                m.title,
                style = Mitra.type.bodyBold.copy(textDecoration = if (m.reached) TextDecoration.LineThrough else null),
                color = if (m.reached) c.muted else c.text,
            )
            Text(m.sub, style = Mitra.type.xs, color = if (m.now) c.accent else c.muted)
        }
        IconButton(onClick = onTick) {
            Icon(
                if (m.reached) MitraIcons.Undo else MitraIcons.Check,
                contentDescription = if (m.reached) "Un-reach ${m.title}" else "Mark ${m.title} reached",
                tint = if (m.reached) c.muted else c.accent,
            )
        }
    }
}

@Composable
private fun ProgressChart(state: GoalDetailUiState) {
    val c = Mitra.colors
    val rupees = state.unit == "₹"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Progress over time")
        MitraCard {
            LineChart(
                values = state.chartValues,
                labels = state.chartLabels,
                goal = state.chartGoal,
                format = { if (rupees) "₹" + localeIN(it) else jsNum(it) },
                modifier = Modifier.height(140.dp),
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendSwatch(state.chartLegend, Modifier.size(10.dp).clip(CircleShape).background(c.accent))
                LegendSwatch("Target", Modifier.width(12.dp).height(2.dp).background(c.muted))
            }
            state.lastLogged?.let { Text(it, style = Mitra.type.xs, color = c.faint, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

@Composable
private fun LegendSwatch(label: String, swatch: Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(swatch)
        Text(label, style = Mitra.type.xs, color = Mitra.colors.muted)
    }
}

@Composable
private fun NextAction(state: GoalDetailUiState, onAction: (GoalDetailAction) -> Unit, onOpenTask: (String) -> Unit, onAddTask: () -> Unit) {
    val c = Mitra.colors
    MitraCard {
        Eyebrow("Next action")
        val next = state.nextTask
        if (next != null) {
            TaskRow(next, onToggle = { onAction(GoalDetailAction.ToggleTask(next.id)) }, onOpen = { onOpenTask(next.id) }, modifier = Modifier.padding(top = 4.dp))
        } else {
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(state.nextAction, style = Mitra.type.bodyBold, color = c.text)
                Text("Set in Edit · or link a task", style = Mitra.type.xs, color = c.muted)
            }
        }
        MitraButton("Add task", onClick = onAddTask, kind = ButtonKind.Outline, icon = MitraIcons.Plus, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun Linked(state: GoalDetailUiState, onOpenTask: (String) -> Unit, onOpenProject: (String) -> Unit, onOpenHabit: (String) -> Unit) {
    val c = Mitra.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Linked · ${state.linkedCount}")
        MitraCard(contentPadding = PaddingValues(0.dp)) {
            if (state.linked.isEmpty()) {
                Text(
                    "Link tasks (Edit task → goal), habits (Edit habit → goal) or projects to show evidence here.",
                    style = Mitra.type.sm, color = c.muted, modifier = Modifier.padding(14.dp),
                )
            }
            state.linked.forEachIndexed { i, l ->
                if (i > 0) HorizontalDivider(color = c.border, thickness = 1.dp)
                val open = when (l.kind) {
                    LinkedKind.Project -> { { onOpenProject(l.id) } }
                    LinkedKind.Task -> { { onOpenTask(l.id) } }
                    LinkedKind.Habit -> { { onOpenHabit(l.id) } }
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClickLabel = "Open", onClick = open)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (l.kind) {
                        LinkedKind.Project -> EmojiBox(l.emoji ?: "🚀", size = 32.dp)
                        LinkedKind.Task -> IconBox(MitraIcons.CheckSquare, tone = if (l.done) Tone.Success else Tone.Neutral, size = 32.dp)
                        LinkedKind.Habit -> IconBox(MitraIcons.byName(l.icon ?: "repeat"), size = 32.dp)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            l.title,
                            style = Mitra.type.body.copy(fontWeight = FontWeight.Medium, textDecoration = if (l.done) TextDecoration.LineThrough else null),
                            color = if (l.done) c.muted else c.text,
                        )
                        Text(l.sub, style = Mitra.type.xs, color = c.muted)
                    }
                    Icon(MitraIcons.ChevronRight, contentDescription = null, tint = c.faint, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) Mitra.colors.danger else Mitra.colors.text
    DropdownMenuItem(
        text = { Text(text, style = Mitra.type.body, color = color) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = color) },
        onClick = onClick,
    )
}
