package tech.beyondscale.mitra.feature.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.ButtonKind
import tech.beyondscale.mitra.designsystem.components.ChipTone
import tech.beyondscale.mitra.designsystem.components.EmptyState
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens
import tech.beyondscale.mitra.domain.model.NOTE_FOLDERS
import tech.beyondscale.mitra.feature.common.EventEffect

@Composable
fun NotesRoute(
    onOpenNote: (String) -> Unit,
    onSearch: () -> Unit,
    onJournal: () -> Unit,
    /** Extra top-bar actions (the shell passes its sync pill and avatar). */
    topBarActions: @Composable RowScope.() -> Unit = {},
    /** A note just created from the form (the shell opens it straight into writing). */
    onNoteCreated: (String) -> Unit = onOpenNote,
    vm: NotesViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    EventEffect(vm.events, vm::onAction)
    LaunchedEffect(vm) { vm.openNote.collect { onNoteCreated(it) } }
    NotesScreen(state, vm::onAction, onOpenNote, onSearch, onJournal, topBarActions)
}

/** Web `NotesScreen` with the Folders & tags filters inline: folder chips, tag chips, note cards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    state: NotesUiState,
    onAction: (NotesAction) -> Unit,
    onOpenNote: (String) -> Unit,
    onSearch: () -> Unit,
    onJournal: () -> Unit,
    topBarActions: @Composable RowScope.() -> Unit = {},
) {
    val c = Mitra.colors
    Column(Modifier.fillMaxSize()) {
        MitraTopBar("Notes") {
            IconButton(onClick = onSearch) { Icon(MitraIcons.Search, contentDescription = "Search notes", tint = c.text) }
            IconButton(onClick = onJournal) { Icon(MitraIcons.Edit, contentDescription = "Journal", tint = c.text) }
            IconButton(onClick = { onAction(NotesAction.OpenForm) }) { Icon(MitraIcons.Plus, contentDescription = "New note", tint = c.accent) }
            topBarActions()
        }
        if (!state.loaded) return@Column
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { onAction(NotesAction.Refresh) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = MitraDimens.pad, end = MitraDimens.pad, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
            ) {
                item(key = "folders") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item { MitraChip("All ${state.allCount}", selected = state.filter == NoteFilter.All, onClick = { onAction(NotesAction.Filter(NoteFilter.All)) }) }
                        items(state.folders, key = { "f:${it.name}" }) { f ->
                            MitraChip("${f.name} ${f.count}", selected = f.selected, onClick = { onAction(NotesAction.Filter(NoteFilter.Folder(f.name))) })
                        }
                    }
                }
                if (state.tags.isNotEmpty()) {
                    item(key = "tags") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(state.tags, key = { "t:${it.name}" }) { t ->
                                MitraChip("#${t.name} ${t.count}", tone = ChipTone.Outline, selected = t.selected, onClick = { onAction(NotesAction.Filter(NoteFilter.Tag(t.name))) })
                            }
                        }
                    }
                }
                when {
                    state.empty -> item(key = "empty") {
                        MitraCard {
                            EmptyState(
                                MitraIcons.Note, "Write your first note", "Or let Mitra draft today's journal.",
                                action = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        MitraButton("New note", onClick = { onAction(NotesAction.OpenForm) }, icon = MitraIcons.Plus)
                                        MitraButton("Journal", onClick = onJournal, kind = ButtonKind.Outline, icon = MitraIcons.Edit)
                                    }
                                },
                            )
                        }
                    }
                    state.rows.isEmpty() -> item(key = "none") {
                        MitraCard { Text("Nothing here yet.", style = Mitra.type.sm, color = c.muted) }
                    }
                    else -> items(state.rows, key = { it.id }) { NoteCard(it) { onOpenNote(it.id) } }
                }
            }
        }
    }
    state.formFolder?.let { folder -> NewNoteSheet(folder, onAction) }
}

@Composable
internal fun NoteCard(row: NoteRowUi, onClick: () -> Unit) {
    val c = Mitra.colors
    MitraCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (row.journal) "Journal" else row.folder, style = Mitra.type.xs, color = c.muted, modifier = Modifier.weight(1f))
            if (row.ai) MitraChip("AI draft", tone = ChipTone.Accent, icon = MitraIcons.Sparkles)
        }
        Text(row.title, style = Mitra.type.bodyBold, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (row.excerpt.isNotBlank()) {
            Text(row.excerpt, style = Mitra.type.sm, color = c.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.tags.forEach { Text(it, style = Mitra.type.xs, color = c.faint) }
            Spacer(Modifier.weight(1f))
            Text(row.updated, style = Mitra.type.xs, color = c.faint)
        }
    }
}

/** Web `NoteForm`: title, folder, tags. Create opens the note in the editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewNoteSheet(defaultFolder: String, onAction: (NotesAction) -> Unit) {
    val c = Mitra.colors
    var title by rememberSaveable { mutableStateOf("") }
    var folder by rememberSaveable { mutableStateOf(defaultFolder) }
    var tags by rememberSaveable { mutableStateOf("") }
    val create = { onAction(NotesAction.Create(title, folder, tags)) }
    ModalBottomSheet(
        onDismissRequest = { onAction(NotesAction.CloseForm) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.bg,
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = MitraDimens.pad, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("New note", style = Mitra.type.h3, color = c.text)
            MitraTextField(title, { title = it }, "Title", placeholder = "Note title", imeAction = ImeAction.Done, onImeAction = create)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items((NOTE_FOLDERS + defaultFolder).distinct()) { f -> MitraChip(f, selected = f == folder, onClick = { folder = f }) }
            }
            MitraTextField(tags, { tags = it }, "Tags", placeholder = "ideas, money")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MitraButton("Cancel", onClick = { onAction(NotesAction.CloseForm) }, kind = ButtonKind.Outline)
                Spacer(Modifier.weight(1f))
                MitraButton("Create", onClick = create, enabled = title.isNotBlank())
            }
        }
    }
}
