package tech.beyondscale.mitra.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.EmptyState
import tech.beyondscale.mitra.designsystem.components.Eyebrow
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.MitraProgressBar
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.model.TaskDraft
import tech.beyondscale.mitra.feature.common.EventEffect

@Composable
fun ProjectDetailRoute(
    id: String,
    onBack: () -> Unit,
    onOpenTask: (String) -> Unit,
    onAddTask: (TaskDraft) -> Unit,
    vm: ProjectDetailViewModel = koinViewModel { parametersOf(id) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    EventEffect(vm.events, vm::onAction, onClose = onBack)
    ProjectDetailScreen(state, vm::onAction, id, onBack, onOpenTask, onAddTask)
}

/** Project detail (web `ProjectDetailScreen`), list view only — the web's kanban board is not ported to the phone. */
@Composable
fun ProjectDetailScreen(
    state: ProjectDetailUiState,
    onAction: (ProjectDetailAction) -> Unit,
    id: String,
    onBack: () -> Unit,
    onOpenTask: (String) -> Unit,
    onAddTask: (TaskDraft) -> Unit,
) {
    val c = Mitra.colors
    var menu by rememberSaveable { mutableStateOf(false) }
    var edit by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val addTask = { onAddTask(TaskDraft("", projectId = id)) }
    val found = state.loaded && !state.notFound

    Column(Modifier.fillMaxSize()) {
        MitraTopBar(if (state.notFound) "Project not found" else "", onBack = onBack) {
            if (found) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(MitraIcons.More, contentDescription = "More actions", tint = c.text) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        OverflowItem("Edit project", MitraIcons.Edit) { menu = false; edit = true }
                        OverflowItem("Add task", MitraIcons.Plus) { menu = false; addTask() }
                        OverflowItem("Delete project", MitraIcons.Trash, danger = true) { menu = false; confirmDelete = true }
                    }
                }
            }
        }
        if (!state.loaded) return@Column
        if (state.notFound) {
            EmptyState(MitraIcons.Folder, "This project doesn't exist", "It may have been deleted.") {
                MitraButton("All projects", onClick = onBack, kind = ButtonKind.Outline, icon = MitraIcons.ArrowLeft)
            }
            return@Column
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") { Header(state, addTask) }
            if (state.rows.isEmpty()) {
                item(key = "empty") {
                    MitraCard(Modifier.padding(horizontal = MitraDimens.pad), contentPadding = PaddingValues(0.dp)) {
                        EmptyState(MitraIcons.CheckSquare, "No tasks yet", "Add the first task to this project.") {
                            MitraButton("Add task", onClick = addTask, icon = MitraIcons.Plus)
                        }
                    }
                }
            }
            val n = state.rows.size
            itemsIndexed(state.rows, key = { _, r -> r.id }) { i, r ->
                Column(Modifier.slice(i, n)) {
                    if (i > 0) HorizontalDivider(color = c.border, thickness = 1.dp, modifier = Modifier.padding(start = 48.dp))
                    SwipeTaskRow(
                        r,
                        onToggle = { onAction(ProjectDetailAction.Toggle(r.id)) },
                        onReschedule = { onAction(ProjectDetailAction.Reschedule(r.id)) },
                        onOpen = { onOpenTask(r.id) },
                    )
                }
            }
        }
    }

    if (edit && found) {
        ProjectFormSheet(
            title = "Edit project",
            confirmLabel = "Save",
            initialName = state.name,
            initialEmoji = state.emoji,
            onSave = { name, emoji -> onAction(ProjectDetailAction.Edit(name, emoji)) },
            onDismiss = { edit = false },
        )
    }
    if (confirmDelete && found) {
        val n = state.open + state.done
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${state.name}”?", style = Mitra.type.h3, color = c.text) },
            text = { Text("$n task${if (n == 1) "" else "s"} will be moved to Inbox, not deleted.", style = Mitra.type.body, color = c.muted) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onAction(ProjectDetailAction.Delete) }) { Text("Delete", color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = c.muted) } },
            containerColor = c.surface,
        )
    }
}

@Composable
private fun Header(state: ProjectDetailUiState, onAddTask: () -> Unit) {
    val c = Mitra.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = MitraDimens.pad).padding(bottom = MitraDimens.gap),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2).semantics { contentDescription = "Icon ${state.emoji}" },
                contentAlignment = Alignment.Center,
            ) { Text(state.emoji, fontSize = 24.sp) }
            Column(Modifier.weight(1f)) {
                Eyebrow("Project")
                Text(state.name, style = Mitra.type.h1, color = c.text, modifier = Modifier.semantics { heading() })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            MitraChip("${state.open} open · ${state.done} done")
        }
        MitraProgressBar(
            state.pct / 100f,
            modifier = Modifier.semantics { contentDescription = "${state.pct}% complete" },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${state.pct}% complete", style = Mitra.type.sm, color = c.muted, modifier = Modifier.weight(1f))
            MitraButton("Add task", onClick = onAddTask, icon = MitraIcons.Plus)
        }
    }
}

/** Rounds the outer corners of the first and last row so consecutive items read as one card. */
@Composable
private fun Modifier.slice(index: Int, size: Int): Modifier {
    val r = MitraDimens.radius
    val top = if (index == 0) r else 0.dp
    val bottom = if (index == size - 1) r else 0.dp
    val shape: Shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
    return this.padding(horizontal = MitraDimens.pad).fillMaxWidth().clip(shape).background(Mitra.colors.surface)
}
