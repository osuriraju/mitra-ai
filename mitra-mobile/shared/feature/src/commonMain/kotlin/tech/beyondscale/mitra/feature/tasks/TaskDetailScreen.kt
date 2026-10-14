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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.CardTone
import tech.beyondscale.mitra.designsystem.components.CheckCircle
import tech.beyondscale.mitra.designsystem.components.ChipTone
import tech.beyondscale.mitra.designsystem.components.EmptyState
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.MitraProgressBar
import tech.beyondscale.mitra.designsystem.components.MitraSegmented
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskDraft
import tech.beyondscale.mitra.domain.model.TaskStatus
import tech.beyondscale.mitra.domain.tasks.nextMonday
import tech.beyondscale.mitra.feature.common.EventEffect
import tech.beyondscale.mitra.feature.common.MitraDatePickerDialog

@Composable
fun TaskDetailRoute(
    id: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onAddTask: (TaskDraft) -> Unit,
    onOpenTask: (String) -> Unit,
    onFocus: () -> Unit,
    onDeleted: (List<Task>) -> Unit,
    vm: TaskDetailViewModel = koinViewModel { parametersOf(id) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    EventEffect(vm.events, vm::onAction)
    val deleted by rememberUpdatedState(onDeleted)
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(vm) {
        vm.deleted.collect { removed ->
            deleted(removed)
            back()
        }
    }
    TaskDetailScreen(state, vm::onAction, id, onBack, onEdit, onAddTask, onOpenTask, onFocus)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskDetailScreen(
    state: TaskDetailUiState,
    onAction: (TaskDetailAction) -> Unit,
    id: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onAddTask: (TaskDraft) -> Unit,
    onOpenTask: (String) -> Unit,
    onFocus: () -> Unit,
) {
    val c = Mitra.colors
    var menu by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var reschedule by rememberSaveable { mutableStateOf(false) }
    var pickDate by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().imePadding()) {
        MitraTopBar("Task", onBack = onBack) {
            if (state.loaded && !state.notFound) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(MitraIcons.More, contentDescription = "More actions", tint = c.text) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        MenuItem("Edit", MitraIcons.Edit) { menu = false; onEdit() }
                        MenuItem("Duplicate", MitraIcons.Columns) { menu = false; onAction(TaskDetailAction.Duplicate) }
                        MenuItem("Delete", MitraIcons.Trash, danger = true) { menu = false; confirmDelete = true }
                    }
                }
            }
        }
        if (!state.loaded) return@Column
        if (state.notFound) {
            EmptyState(MitraIcons.CheckSquare, "Task not found", "It may have been deleted.") {
                MitraButton("Back", onClick = onBack, kind = ButtonKind.Outline, icon = MitraIcons.ArrowLeft)
            }
            return@Column
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = MitraDimens.pad).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
        ) {
            // Header
            Row(verticalAlignment = Alignment.Top) {
                CheckCircle(
                    checked = state.done,
                    onToggle = { onAction(TaskDetailAction.ToggleDone) },
                    label = if (state.done) "Mark ${state.title} not done" else "Complete ${state.title}",
                    modifier = Modifier.padding(end = 4.dp),
                )
                Column(Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        state.title,
                        style = Mitra.type.h2.copy(textDecoration = if (state.done) TextDecoration.LineThrough else null),
                        color = if (state.done) c.muted else c.text,
                        modifier = Modifier.semantics { heading() },
                    )
                    val chips = state.priChip != null || state.project != null || state.ai
                    if (chips) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            state.priChip?.let { (text, tone) -> MitraChip(text, tone = tone, icon = MitraIcons.Flag) }
                            state.project?.let { p -> MitraChip(p.name, icon = MitraIcons.Folder) }
                            if (state.ai) MitraChip("Created by AI", tone = ChipTone.Accent, icon = MitraIcons.Sparkles)
                        }
                    }
                }
            }

            if (reschedule && state.today.isNotEmpty()) {
                MitraCard(tone = CardTone.Soft) {
                    Text("Reschedule to", style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    val t = state.today
                    val options = listOf(
                        "Today" to t, "Tomorrow" to addDays(t, 1), "Next Monday" to nextMonday(t), "Next week" to addDays(t, 7), "No date" to null,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        options.forEach { (label, due) ->
                            MitraChip(label, tone = ChipTone.Outline, onClick = { onAction(TaskDetailAction.Reschedule(due, label)); reschedule = false })
                        }
                        MitraChip("Pick date", tone = ChipTone.Outline, icon = MitraIcons.Calendar, onClick = { pickDate = true })
                    }
                }
            }

            // Fields
            MitraCard(contentPadding = PaddingValues(0.dp)) {
                FieldRow(MitraIcons.Clock, "Due", onClick = { reschedule = !reschedule }) {
                    Text(
                        buildAnnotatedString {
                            append(state.dueLine)
                            if (state.overdue) withStyle(SpanStyle(color = c.danger)) { append(" · overdue") }
                        },
                        style = Mitra.type.sm, color = c.muted,
                    )
                }
                Divider()
                FieldRow(MitraIcons.Timer, "Estimate", onClick = onEdit) { Text(state.estimateLine, style = Mitra.type.sm, color = c.muted) }
                Divider()
                FieldRow(MitraIcons.Repeat, "Repeat", onClick = onEdit) { Text(state.repeatLine, style = Mitra.type.sm, color = c.muted) }
                Divider()
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconBox(MitraIcons.Layers)
                        Text("Status", style = Mitra.type.bodyBold, color = c.text)
                    }
                    MitraSegmented(
                        options = TaskStatus.entries.map { it.label },
                        selectedIndex = state.status.ordinal,
                        onSelect = { onAction(TaskDetailAction.SetStatus(TaskStatus.entries[it])) },
                    )
                }
            }

            // Parent
            state.parent?.let { p ->
                MitraCard(tone = CardTone.Soft, onClick = { onOpenTask(p.id) }, contentPadding = PaddingValues(12.dp)) {
                    Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(MitraIcons.Layers, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
                        Text(
                            buildAnnotatedString {
                                append("Subtask of ")
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(p.title) }
                                append(" · ${p.progress} done")
                            },
                            style = Mitra.type.sm, color = c.text, modifier = Modifier.weight(1f),
                        )
                        Icon(MitraIcons.ChevronRight, contentDescription = null, tint = c.faint, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Subtasks
            Subtasks(state, onAction, id, onAddTask, onOpenTask)

            MitraTextField(
                value = state.notes,
                onValueChange = { onAction(TaskDetailAction.Notes(it)) },
                label = "Notes",
                placeholder = "Details, links, context…",
                singleLine = false,
                minLines = 3,
                imeAction = ImeAction.Default,
            )

            if (state.createdLine.isNotEmpty()) Text(state.createdLine, style = Mitra.type.xs, color = c.faint)
        }

        Row(
            Modifier.fillMaxWidth().background(c.bg).padding(horizontal = MitraDimens.pad, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MitraButton("Focus", onClick = onFocus, kind = ButtonKind.Outline, icon = MitraIcons.Play, modifier = Modifier.weight(1f))
            MitraButton(if (state.done) "Reopen" else "Complete", onClick = { onAction(TaskDetailAction.ToggleDone) }, icon = MitraIcons.Check, modifier = Modifier.weight(1f))
        }
    }

    if (pickDate) {
        MitraDatePickerDialog(
            state.due ?: state.today.ifEmpty { null },
            onPick = { onAction(TaskDetailAction.SetDue(it)); reschedule = false },
            onDismiss = { pickDate = false },
        )
    }
    if (confirmDelete) {
        val n = state.subProgress.total
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${state.title}”?", style = Mitra.type.h3, color = c.text) },
            text = {
                Text(
                    if (n > 0) "$n subtask${if (n > 1) "s" else ""} will be deleted too. You can undo for a few seconds." else "You can undo for a few seconds.",
                    style = Mitra.type.body, color = c.muted,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onAction(TaskDetailAction.Delete) }) { Text("Delete", color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = c.muted) } },
            containerColor = c.surface,
        )
    }
}

@Composable
private fun Subtasks(state: TaskDetailUiState, onAction: (TaskDetailAction) -> Unit, id: String, onAddTask: (TaskDraft) -> Unit, onOpenTask: (String) -> Unit) {
    val c = Mitra.colors
    val sp = state.subProgress
    val allDone = sp.total > 0 && sp.done == sp.total
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Subtasks · ${sp.done} of ${sp.total}${if (allDone && !state.done) " · all done" else ""}",
                style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = c.muted,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Text(
                "Detailed subtask",
                style = Mitra.type.xs.copy(textDecoration = TextDecoration.Underline), color = c.accent,
                modifier = Modifier.heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { onAddTask(TaskDraft("", parentId = id, projectId = state.projectId, due = state.due)) }
                    .padding(horizontal = 4.dp, vertical = 16.dp),
            )
        }
        if (sp.total > 0) MitraProgressBar(sp.done.toFloat() / sp.total, color = if (allDone) c.success else c.accent, height = 4.dp)
        MitraCard(contentPadding = PaddingValues(0.dp)) {
            state.subtasks.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(color = c.border, thickness = 1.dp, modifier = Modifier.padding(start = 48.dp))
                TaskRow(r, onToggle = { onAction(TaskDetailAction.ToggleChild(r.id)) }, onOpen = { onOpenTask(r.id) })
            }
            if (state.subtasks.isNotEmpty()) HorizontalDivider(color = c.border, thickness = 1.dp)
            SubtaskComposer { onAction(TaskDetailAction.AddSubtask(it)) }
        }
    }
}

@Composable
private fun SubtaskComposer(onSubmit: (String) -> Unit) {
    val c = Mitra.colors
    var text by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank()) {
            onSubmit(text)
            text = ""
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Add a subtask…", style = Mitra.type.body, color = c.faint) },
        leadingIcon = { Icon(MitraIcons.Plus, contentDescription = null, tint = c.muted) },
        trailingIcon = if (text.isNotBlank()) {
            { IconButton(onClick = submit) { Icon(MitraIcons.ArrowRight, contentDescription = "Add subtask", tint = c.accent) } }
        } else null,
        textStyle = Mitra.type.body.copy(color = c.text),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.surface, unfocusedBorderColor = c.surface,
            focusedContainerColor = c.surface, unfocusedContainerColor = c.surface, cursorColor = c.accent,
        ),
    )
}

@Composable
private fun FieldRow(icon: ImageVector, title: String, onClick: () -> Unit, value: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClickLabel = "Change $title", onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBox(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = Mitra.type.bodyBold, color = Mitra.colors.text)
            value()
        }
        Icon(MitraIcons.ChevronRight, contentDescription = null, tint = Mitra.colors.faint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun IconBox(icon: ImageVector) {
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Mitra.colors.surface2), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Mitra.colors.muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = Mitra.colors.border, thickness = 1.dp)

@Composable
private fun MenuItem(text: String, icon: ImageVector, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) Mitra.colors.danger else Mitra.colors.text
    DropdownMenuItem(
        text = { Text(text, style = Mitra.type.body, color = color) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = color) },
        onClick = onClick,
    )
}
