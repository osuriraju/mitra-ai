package tech.beyondscale.mitra.feature.tasks

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.core.fmtTime
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.ChipTone
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.MitraSegmented
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.RECURRENCES
import tech.beyondscale.mitra.feature.common.MitraDatePickerDialog
import tech.beyondscale.mitra.feature.common.MitraTimePickerDialog

private val PRI_OPTIONS = listOf<Priority?>(null, Priority.High, Priority.Med, Priority.Low)

/** Create/edit task form (web `TaskForm`) as a full-height bottom sheet; nothing is shown while the form is closed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskFormSheet(state: TaskEditorUiState, onAction: (TaskEditorAction) -> Unit) {
    if (!state.form.open) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onAction(TaskEditorAction.Dismiss) }
    }
    ModalBottomSheet(
        onDismissRequest = { onAction(TaskEditorAction.Dismiss) },
        sheetState = sheetState,
        containerColor = Mitra.colors.bg,
    ) {
        TaskForm(state, onAction, onCancel = dismiss)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskForm(state: TaskEditorUiState, onAction: (TaskEditorAction) -> Unit, onCancel: () -> Unit) {
    val f = state.form
    val c = Mitra.colors
    val today = state.today
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        titleFocus.requestFocus()
        keyboard?.show()
    }

    Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = MitraDimens.pad),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(state.heading, style = Mitra.type.h2, color = c.text, modifier = Modifier.semantics { heading() })
            MitraTextField(
                value = f.title,
                onValueChange = { onAction(TaskEditorAction.Title(it)) },
                label = "Title",
                placeholder = "What needs doing?",
                error = f.error,
                imeAction = ImeAction.Done,
                onImeAction = { onAction(TaskEditorAction.Save) },
                fieldModifier = Modifier.focusRequester(titleFocus),
            )

            Field("Project") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    MitraChip("Inbox", icon = MitraIcons.Inbox, tone = ChipTone.Outline, selected = f.projectId == null, onClick = { onAction(TaskEditorAction.Project(null)) })
                    state.projects.forEach { p ->
                        MitraChip("${p.emoji} ${p.name}", tone = ChipTone.Outline, selected = f.projectId == p.id, onClick = { onAction(TaskEditorAction.Project(p.id)) })
                    }
                }
            }

            if (today.isNotEmpty()) {
                Field("Due date") {
                    val quick = listOf("Today" to today, "Tomorrow" to addDays(today, 1), "Next week" to addDays(today, 7))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        quick.forEach { (label, day) ->
                            MitraChip(label, tone = ChipTone.Outline, selected = f.due == day, onClick = { onAction(TaskEditorAction.Due(day)) })
                        }
                        MitraChip("No date", tone = ChipTone.Outline, selected = f.due == null, onClick = { onAction(TaskEditorAction.Due(null)) })
                        val custom = f.due?.takeIf { d -> quick.none { it.second == d } }
                        MitraChip(
                            custom?.let { fmtDay(it, today) } ?: "Pick date",
                            tone = ChipTone.Outline,
                            icon = MitraIcons.Calendar,
                            selected = custom != null,
                            onClick = { pickDate = true },
                        )
                    }
                }
            }

            Field("Time") {
                val time = f.time
                if (time == null) {
                    MitraChip("Add time", tone = ChipTone.Outline, icon = MitraIcons.Clock, onClick = { pickTime = true })
                } else {
                    MitraChip(
                        fmtTime(time), icon = MitraIcons.Clock, selected = true,
                        onClick = { pickTime = true },
                        onRemove = { onAction(TaskEditorAction.Time(null)) },
                    )
                }
            }

            Field("Priority") {
                MitraSegmented(
                    options = PRI_OPTIONS.map { it?.label ?: "None" },
                    selectedIndex = PRI_OPTIONS.indexOf(f.pri),
                    onSelect = { onAction(TaskEditorAction.Pri(PRI_OPTIONS[it])) },
                )
            }

            Row(verticalAlignment = Alignment.Bottom) {
                MitraTextField(
                    value = f.est,
                    onValueChange = { onAction(TaskEditorAction.Est(it)) },
                    label = "Estimate",
                    placeholder = "30",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text("minutes", style = Mitra.type.body, color = c.muted, modifier = Modifier.padding(bottom = 16.dp))
            }

            Field("Repeat") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    MitraChip("Does not repeat", tone = ChipTone.Outline, selected = f.recurring == null, onClick = { onAction(TaskEditorAction.Recurring(null)) })
                    RECURRENCES.forEach { r ->
                        MitraChip(r, tone = ChipTone.Outline, icon = MitraIcons.Repeat, selected = f.recurring == r, onClick = { onAction(TaskEditorAction.Recurring(r)) })
                    }
                }
            }

            Field("Subtask of") {
                ParentPicker(state, onAction)
                if (f.parentId != null) {
                    Text("Subtasks are full tasks: own status, priority and due date.", style = Mitra.type.xs, color = c.muted)
                }
            }

            MitraTextField(
                value = f.notes,
                onValueChange = { onAction(TaskEditorAction.Notes(it)) },
                label = "Notes",
                placeholder = "Details, links, context…",
                singleLine = false,
                minLines = 3,
                imeAction = ImeAction.Default,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = MitraDimens.pad, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MitraButton("Cancel", onClick = onCancel, kind = ButtonKind.Outline, modifier = Modifier.weight(1f))
            MitraButton(if (f.editId != null) "Save" else "Add task", onClick = { onAction(TaskEditorAction.Save) }, modifier = Modifier.weight(1f))
        }
    }

    if (pickDate) MitraDatePickerDialog(f.due ?: today.ifEmpty { null }, onPick = { onAction(TaskEditorAction.Due(it)) }, onDismiss = { pickDate = false })
    if (pickTime) MitraTimePickerDialog(f.time, onPick = { onAction(TaskEditorAction.Time(it)) }, onDismiss = { pickTime = false })
}

@Composable
private fun ParentPicker(state: TaskEditorUiState, onAction: (TaskEditorAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = state.parentOptions.firstOrNull { it.id == state.form.parentId }
    Box {
        MitraChip(
            "${current?.title ?: "None — top-level task"} ▾",
            tone = ChipTone.Outline,
            icon = MitraIcons.Layers,
            selected = current != null,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("None — top-level task", style = Mitra.type.body) }, onClick = { open = false; onAction(TaskEditorAction.Parent(null)) })
            state.parentOptions.forEach { p ->
                DropdownMenuItem(text = { Text(p.title, style = Mitra.type.body, maxLines = 1) }, onClick = { open = false; onAction(TaskEditorAction.Parent(p.id)) })
            }
        }
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = Mitra.type.sm.copy(fontWeight = FontWeight.SemiBold), color = Mitra.colors.muted)
        content()
    }
}
