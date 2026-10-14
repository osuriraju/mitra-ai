package tech.beyondscale.mitra.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.designsystem.components.BannerTone
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.ChipTone
import tech.beyondscale.mitra.designsystem.components.EmptyState
import tech.beyondscale.mitra.designsystem.components.Eyebrow
import tech.beyondscale.mitra.designsystem.components.MitraBanner
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.MitraProgressBar
import tech.beyondscale.mitra.designsystem.components.MitraSegmented
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.TaskChange
import tech.beyondscale.mitra.domain.model.TaskDraft
import tech.beyondscale.mitra.domain.tasks.DueFilter
import tech.beyondscale.mitra.domain.tasks.TaskFilters
import tech.beyondscale.mitra.feature.common.EventEffect

@Composable
fun TasksRoute(
    onOpenTask: (String) -> Unit,
    onOpenProject: (String) -> Unit,
    onAddTask: (TaskDraft) -> Unit,
    onFocus: () -> Unit,
    /** Null (the default) opens the "New project" sheet hosted here. */
    onNewProject: (() -> Unit)? = null,
    /** Extra top-bar actions after Add (the shell passes its sync pill and avatar). */
    topBarActions: @Composable RowScope.() -> Unit = {},
    vm: TasksViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    EventEffect(vm.events, vm::onAction)
    var newProject by rememberSaveable { mutableStateOf(false) }
    TasksScreen(state, vm::onAction, onOpenTask, onOpenProject, onAddTask, onFocus, onNewProject ?: { newProject = true }, topBarActions)
    if (newProject) {
        ProjectFormSheet(
            title = "New project",
            confirmLabel = "Create project",
            onSave = { name, emoji -> vm.onAction(TasksAction.CreateProject(name, emoji)) },
            onDismiss = { newProject = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun TasksScreen(
    state: TasksUiState,
    onAction: (TasksAction) -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenProject: (String) -> Unit,
    onAddTask: (TaskDraft) -> Unit,
    onFocus: () -> Unit,
    onNewProject: () -> Unit = {},
    topBarActions: @Composable RowScope.() -> Unit = {},
) {
    val today = state.today
    val tomorrow = if (today.isEmpty()) null else addDays(today, 1)
    val addForTab = {
        when (state.tab) {
            TasksTab.Inbox -> onAddTask(TaskDraft(""))
            TasksTab.Today -> onAddTask(TaskDraft("", due = today.ifEmpty { null }))
            TasksTab.Upcoming -> onAddTask(TaskDraft("", due = tomorrow))
            TasksTab.Projects -> onNewProject()
        }
    }
    val rowActions = RowActions(
        toggle = { onAction(TasksAction.Toggle(it)) },
        reschedule = { onAction(TasksAction.Reschedule(it)) },
        open = onOpenTask,
        // Taps while selecting toggle membership; the first long-press starts selection.
        longPress = { id -> onAction(if (state.selection.isEmpty()) TasksAction.LongPress(id) else TasksAction.ToggleSelect(id)) },
        selection = state.selection,
    )
    @Suppress("DEPRECATION")
    BackHandler(enabled = state.selection.isNotEmpty()) { onAction(TasksAction.ClearSelection) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().imePadding()) {
        MitraTopBar(state.tab.title()) {
            IconButton(onClick = addForTab) {
                Icon(MitraIcons.Plus, contentDescription = if (state.tab == TasksTab.Projects) "New project" else "Add task", tint = Mitra.colors.accent)
            }
            topBarActions()
        }
        val counts = state.counts
        if (state.selection.isEmpty()) {
            MitraSegmented(
                options = listOf("Inbox ${counts.inbox}", "Today ${counts.today}", "Upcoming ${counts.upcoming}", "Projects ${counts.projects}"),
                selectedIndex = state.tab.ordinal,
                onSelect = { onAction(TasksAction.SelectTab(TasksTab.entries[it])) },
                modifier = Modifier.padding(horizontal = MitraDimens.pad),
            )
        } else {
            SelectionBar(state, onAction, onDelete = { confirmDelete = true })
        }
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { onAction(TasksAction.Refresh) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = MitraDimens.gap, bottom = 24.dp),
            ) {
                when (state.tab) {
                    TasksTab.Today -> todayTab(state, onAction, rowActions, onAddTask, onFocus)
                    TasksTab.Upcoming -> upcomingTab(state, onAction, rowActions, onAddTask)
                    TasksTab.Inbox -> inboxTab(state, onAction, rowActions, onAddTask)
                    TasksTab.Projects -> projectsTab(state, onOpenProject, onNewProject)
                }
            }
        }
        if (state.tab == TasksTab.Inbox) InboxComposer { onAction(TasksAction.AddToInbox(it)) }
    }
    if (confirmDelete && state.selection.isNotEmpty()) {
        val n = state.selection.size
        val c = Mitra.colors
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete $n task${if (n == 1) "" else "s"}?", style = Mitra.type.h3, color = c.text) },
            text = { Text("Their subtasks are deleted too. You can undo right after.", style = Mitra.type.body, color = c.muted) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onAction(TasksAction.BulkDelete) }) { Text("Delete", color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = c.muted) } },
            containerColor = c.surface,
        )
    }
}

/** Contextual bar shown instead of the tab row while tasks are selected. */
@Composable
private fun SelectionBar(state: TasksUiState, onAction: (TasksAction) -> Unit, onDelete: () -> Unit) {
    val c = Mitra.colors
    var moveOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = MitraDimens.pad - 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onAction(TasksAction.ClearSelection) }) { Icon(MitraIcons.X, contentDescription = "Clear selection", tint = c.text) }
        Text(
            "${state.selection.size} selected",
            style = Mitra.type.bodyBold,
            color = c.text,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        IconButton(onClick = { onAction(TasksAction.BulkComplete) }) { Icon(MitraIcons.Check, contentDescription = "Complete selected", tint = c.accent) }
        Box {
            IconButton(onClick = { moveOpen = true }) { Icon(MitraIcons.Folder, contentDescription = "Move selected to project", tint = c.accent) }
            DropdownMenu(expanded = moveOpen, onDismissRequest = { moveOpen = false }) {
                DropdownMenuItem(text = { Text("Inbox", style = Mitra.type.body) }, onClick = { moveOpen = false; onAction(TasksAction.BulkMove(null)) })
                state.projectOptions.forEach { p ->
                    DropdownMenuItem(
                        text = { Text("${p.emoji} ${p.name}", style = Mitra.type.body) },
                        onClick = { moveOpen = false; onAction(TasksAction.BulkMove(p.id)) },
                    )
                }
            }
        }
        IconButton(onClick = onDelete) { Icon(MitraIcons.Trash, contentDescription = "Delete selected", tint = c.danger) }
    }
}

private fun TasksTab.title() = when (this) {
    TasksTab.Today -> "Tasks"
    else -> label
}

private class RowActions(
    val toggle: (String) -> Unit,
    val reschedule: (String) -> Unit,
    val open: (String) -> Unit,
    val longPress: (String) -> Unit,
    val selection: Set<String>,
) {
    val selecting get() = selection.isNotEmpty()
}

@Composable
private fun SelectableRow(r: TaskRowUi, a: RowActions) {
    val selected = r.id in a.selection
    SwipeTaskRow(
        r,
        onToggle = { if (a.selecting) a.longPress(r.id) else a.toggle(r.id) },
        onReschedule = { a.reschedule(r.id) },
        onOpen = { if (a.selecting) a.longPress(r.id) else a.open(r.id) },
        onLongPress = { if (!a.selecting) a.longPress(r.id) },
        selected = selected,
        selecting = a.selecting,
    )
}

/* ---------------- tabs ---------------- */

private fun LazyListScope.todayTab(
    state: TasksUiState,
    onAction: (TasksAction) -> Unit,
    rows: RowActions,
    onAddTask: (TaskDraft) -> Unit,
    onFocus: () -> Unit,
) {
    item(key = "filters") { FiltersRow(state, onAction) }
    if (state.overdue.isNotEmpty()) {
        item(key = "h:overdue") { Header("Overdue") }
        taskRows(state.overdue, rows)
        item(key = "overdue:move") {
            CardFooter {
                MitraButton("Move all to today", onClick = { onAction(TasksAction.MoveOverdueToToday) }, kind = ButtonKind.Soft, icon = MitraIcons.Clock)
            }
        }
        item(key = "gap:overdue") { Gap() }
    }
    item(key = "h:today") { Header("Today", action = "${state.openCount} open · ${state.doneCount} done") }
    val visible = state.todayRows.filter { state.showDone || !it.done }
    if (state.todayRows.isEmpty()) {
        item(key = "today:empty") {
            Card {
                MitraCard(contentPadding = PaddingValues(0.dp)) {
                    EmptyState(
                        icon = MitraIcons.CheckSquare,
                        title = "No tasks for today",
                        body = "Add one, or pull something forward from Upcoming. Mitra will keep this screen calm.",
                    ) { MitraButton("Add task", onClick = { onAddTask(TaskDraft("", due = state.today)) }, icon = MitraIcons.Plus) }
                }
            }
        }
    } else {
        taskRows(visible, rows, closed = state.doneCount == 0)
    }
    if (state.doneCount > 0) {
        item(key = "today:showdone") {
            CardFooter(single = visible.isEmpty()) {
                Text(
                    "${if (state.showDone) "Hide" else "Show"} ${state.doneCount} completed",
                    style = Mitra.type.sm.copy(textDecoration = TextDecoration.Underline),
                    color = Mitra.colors.accent,
                    modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { onAction(TasksAction.ToggleShowDone) }
                        .padding(horizontal = 4.dp, vertical = 14.dp),
                )
            }
        }
    }
    item(key = "gap:today") { Gap() }
    item(key = "kpis") {
        Card {
            MitraCard {
                Row(Modifier.fillMaxWidth()) {
                    Kpi("Planned today", state.planned, Modifier.weight(1f))
                    Kpi("Done", state.doneTime, Modifier.weight(1f))
                }
                MitraProgressBar(state.plannedFraction)
                Text("Estimates come from your own task estimates.", style = Mitra.type.xs, color = Mitra.colors.muted)
            }
        }
    }
    item(key = "gap:kpis") { Gap() }
    item(key = "focus") {
        Card {
            MitraCard {
                Eyebrow("Focus")
                Text(state.focusLine, style = Mitra.type.bodyBold, color = Mitra.colors.text)
                MitraButton("Focus mode", onClick = onFocus, icon = MitraIcons.Play)
            }
        }
    }
}

private fun LazyListScope.upcomingTab(state: TasksUiState, onAction: (TasksAction) -> Unit, rows: RowActions, onAddTask: (TaskDraft) -> Unit) {
    item(key = "filters") { FiltersRow(state, onAction) }
    if (state.upcoming.isEmpty()) {
        item(key = "upcoming:empty") {
            Card {
                MitraCard(contentPadding = PaddingValues(0.dp)) {
                    EmptyState(
                        icon = MitraIcons.Calendar,
                        title = "Nothing scheduled — that is fine.",
                        body = "Tasks with a future due date show up here grouped by day.",
                    ) {
                        MitraButton("Schedule a task", onClick = { onAddTask(TaskDraft("", due = addDays(state.today, 1))) }, icon = MitraIcons.Plus)
                    }
                }
            }
        }
    }
    state.upcoming.forEach { g ->
        item(key = "h:${g.key}") { Header(g.label) }
        taskRows(g.rows, rows)
        item(key = "gap:${g.key}") { Gap() }
    }
}

private fun LazyListScope.inboxTab(state: TasksUiState, onAction: (TasksAction) -> Unit, rows: RowActions, onAddTask: (TaskDraft) -> Unit) {
    val n = state.inbox.size
    if (n == 0) {
        item(key = "inbox:empty") {
            Card {
                MitraCard(contentPadding = PaddingValues(0.dp)) {
                    EmptyState(
                        icon = MitraIcons.Inbox,
                        title = "Inbox zero — nice.",
                        body = "New captures from Quick add and the widget land here until you file them.",
                    ) { MitraButton("Add task", onClick = { onAddTask(TaskDraft("")) }, icon = MitraIcons.Plus) }
                }
            }
        }
        return
    }
    item(key = "inbox:banner") {
        MitraBanner(
            "$n unsorted capture${if (n > 1) "s" else ""}. Give each a project or a date.",
            tone = BannerTone.Info,
            modifier = Modifier.padding(horizontal = MitraDimens.pad).padding(bottom = MitraDimens.gap),
        )
    }
    itemsIndexed(state.inbox, key = { _, r -> r.id }) { i, r ->
        Column(Modifier.cardSlice(i, n)) {
            if (i > 0) RowDivider()
            SelectableRow(r, rows)
            TriageChips(r, state, onAction)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TriageChips(r: TaskRowUi, state: TasksUiState, onAction: (TasksAction) -> Unit) {
    fun triage(change: TaskChange, label: String) = onAction(TasksAction.Triage(r.id, change, label))
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 48.dp, end = 12.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        MitraChip("Today", tone = ChipTone.Outline, icon = MitraIcons.Sun, onClick = { triage(TaskChange.Due(state.today), "Today") })
        MitraChip("Tomorrow", tone = ChipTone.Outline, icon = MitraIcons.Clock, onClick = { triage(TaskChange.Due(addDays(state.today, 1)), "Tomorrow") })
        state.triageProjects.forEach { p ->
            MitraChip(p.name, tone = ChipTone.Outline, icon = MitraIcons.Folder, onClick = { triage(TaskChange.ProjectId(p.id), p.name) })
        }
        MitraChip("Someday", tone = ChipTone.Outline, icon = MitraIcons.Archive, onClick = { triage(TaskChange.Someday(true), "Someday") })
    }
}

private fun LazyListScope.projectsTab(state: TasksUiState, onOpenProject: (String) -> Unit, onNewProject: () -> Unit) {
    val cells: List<ProjectCardUi?> = state.projects + null
    cells.chunked(2).forEachIndexed { i, pair ->
        item(key = "projects:${pair.firstOrNull()?.id ?: "new"}:$i") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = MitraDimens.pad).padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                pair.forEach { p ->
                    val m = Modifier.weight(1f).heightIn(min = 150.dp)
                    if (p != null) ProjectCard(p, m) { onOpenProject(p.id) } else NewProjectCard(m, onNewProject)
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ProjectCard(p: ProjectCardUi, modifier: Modifier, onClick: () -> Unit) {
    val c = Mitra.colors
    MitraCard(modifier, onClick = onClick) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
            Text(p.emoji, fontSize = 20.sp)
        }
        Text(p.name, style = Mitra.type.bodyBold, color = c.text, maxLines = 2)
        Text("${p.open} open task${if (p.open == 1) "" else "s"}", style = Mitra.type.sm, color = c.muted)
        Spacer(Modifier.weight(1f, fill = false))
        MitraProgressBar(p.pct / 100f, height = 4.dp)
        Text("${p.pct}% complete", style = Mitra.type.xs, color = c.muted)
    }
}

@Composable
private fun NewProjectCard(modifier: Modifier, onClick: () -> Unit) {
    val c = Mitra.colors
    val shape = RoundedCornerShape(MitraDimens.radius)
    Column(
        modifier
            .clip(shape)
            .drawBehind {
                val r = MitraDimens.radius.toPx()
                drawRoundRect(
                    color = c.faint,
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))),
                )
            }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(MitraDimens.pad),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(MitraIcons.Plus, contentDescription = null, tint = c.muted)
        Text("New project", style = Mitra.type.bodyBold, color = c.muted)
    }
}

/* ---------------- shared pieces ---------------- */

/** Task rows rendered as one card: each row is a lazy item keyed by task id. */
private fun LazyListScope.taskRows(list: List<TaskRowUi>, actions: RowActions, closed: Boolean = true) {
    val n = list.size
    itemsIndexed(list, key = { _, r -> r.id }) { i, r ->
        Column(Modifier.cardSlice(i, n, closeBottom = closed)) {
            if (i > 0) RowDivider()
            SelectableRow(r, actions)
        }
    }
}

/** Rounds the outer corners of the first and last row so consecutive items read as one card. */
@Composable
private fun Modifier.cardSlice(index: Int, size: Int, closeBottom: Boolean = true): Modifier {
    val r = MitraDimens.radius
    val top = if (index == 0) r else 0.dp
    val bottom = if (index == size - 1 && closeBottom) r else 0.dp
    val shape: Shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
    return this.padding(horizontal = MitraDimens.pad).fillMaxWidth().clip(shape).background(Mitra.colors.surface)
}

/** A footer slice that closes a [taskRows] card (or stands alone when [single]). */
@Composable
private fun CardFooter(single: Boolean = false, content: @Composable () -> Unit) {
    val r = MitraDimens.radius
    val shape = if (single) RoundedCornerShape(r) else RoundedCornerShape(bottomStart = r, bottomEnd = r)
    Box(
        Modifier.padding(horizontal = MitraDimens.pad).fillMaxWidth().clip(shape).background(Mitra.colors.surface)
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
    ) { content() }
}

@Composable
private fun Card(content: @Composable () -> Unit) = Box(Modifier.padding(horizontal = MitraDimens.pad).fillMaxWidth()) { content() }

@Composable
private fun RowDivider() = HorizontalDivider(color = Mitra.colors.border, thickness = 1.dp, modifier = Modifier.padding(start = 48.dp))

@Composable
private fun Gap() = Spacer(Modifier.height(MitraDimens.gap))

@Composable
private fun Header(title: String, action: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = MitraDimens.pad).padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Mitra.type.h3, color = Mitra.colors.text, modifier = Modifier.weight(1f).semantics { heading() })
        if (action != null) Text(action, style = Mitra.type.sm, color = Mitra.colors.muted)
    }
}

@Composable
private fun Kpi(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = Mitra.type.xs.copy(fontWeight = FontWeight.SemiBold), color = Mitra.colors.muted)
        Text(value, style = Mitra.type.numLg, color = Mitra.colors.text)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FiltersRow(state: TasksUiState, onAction: (TasksAction) -> Unit) {
    val f = state.filters
    fun set(n: TaskFilters) = onAction(TasksAction.SetFilters(n))
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = MitraDimens.pad).padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val project = state.projectOptions.firstOrNull { it.id == f.projectId }
        FilterDropdown(
            label = project?.let { "${it.emoji} ${it.name}" } ?: "Project",
            icon = MitraIcons.Folder,
            active = f.projectId != null,
            options = listOf<Pair<String, () -> Unit>>("All projects" to { set(f.copy(projectId = null)) }) +
                state.projectOptions.map { p -> "${p.emoji} ${p.name}" to { set(f.copy(projectId = p.id)) } },
        )
        FilterDropdown(
            label = f.pri?.label ?: "Priority",
            icon = MitraIcons.Flag,
            active = f.pri != null,
            options = listOf<Pair<String, () -> Unit>>("Any priority" to { set(f.copy(pri = null)) }) +
                Priority.entries.map { p -> p.label to { set(f.copy(pri = p)) } },
        )
        FilterDropdown(
            label = f.due?.label ?: "Due",
            icon = MitraIcons.Clock,
            active = f.due != null,
            options = listOf<Pair<String, () -> Unit>>("Any due date" to { set(f.copy(due = null)) }) +
                DueFilter.entries.map { d -> d.label to { set(f.copy(due = d)) } },
        )
        if (f.active) MitraChip("Clear", tone = ChipTone.Outline, icon = MitraIcons.X, onClick = { set(TaskFilters()) })
    }
}

@Composable
private fun FilterDropdown(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, options: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        MitraChip("$label ▾", tone = ChipTone.Outline, icon = icon, selected = active, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, pick) ->
                DropdownMenuItem(text = { Text(text, style = Mitra.type.body) }, onClick = { open = false; pick() })
            }
        }
    }
}

@Composable
private fun InboxComposer(onSubmit: (String) -> Unit) {
    val c = Mitra.colors
    var text by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank()) {
            onSubmit(text)
            text = ""
        }
    }
    Row(
        Modifier.fillMaxWidth().background(c.bg).padding(horizontal = MitraDimens.pad, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("Add to inbox…", style = Mitra.type.body, color = c.faint) },
            leadingIcon = { Icon(MitraIcons.Plus, contentDescription = null, tint = c.muted) },
            textStyle = Mitra.type.body.copy(color = c.text),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            shape = RoundedCornerShape(MitraDimens.radiusSm),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.accent, unfocusedBorderColor = c.border,
                focusedContainerColor = c.surface, unfocusedContainerColor = c.surface, cursorColor = c.accent,
            ),
        )
        if (text.isNotBlank()) {
            IconButton(onClick = submit) { Icon(MitraIcons.ArrowRight, contentDescription = "Add to inbox", tint = c.accent) }
        }
    }
}
