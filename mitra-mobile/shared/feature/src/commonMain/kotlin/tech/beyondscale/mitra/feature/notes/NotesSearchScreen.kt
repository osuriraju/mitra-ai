package tech.beyondscale.mitra.feature.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import tech.beyondscale.mitra.designsystem.components.EmptyState
import tech.beyondscale.mitra.designsystem.components.MitraButton
import tech.beyondscale.mitra.designsystem.components.MitraCard
import tech.beyondscale.mitra.designsystem.components.MitraChip
import tech.beyondscale.mitra.designsystem.components.MitraTextField
import tech.beyondscale.mitra.designsystem.components.MitraTopBar
import tech.beyondscale.mitra.designsystem.components.SectionHeader
import tech.beyondscale.mitra.designsystem.icons.MitraIcons
import tech.beyondscale.mitra.designsystem.theme.Mitra
import tech.beyondscale.mitra.designsystem.theme.MitraDimens

@Composable
fun NotesSearchRoute(
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenGoal: (String) -> Unit,
    onOpenHabit: (String) -> Unit,
    vm: NotesSearchViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.openNote.collect { onOpenNote(it) } }
    NotesSearchScreen(state, vm::onAction, onBack) { row ->
        when (row.kind) {
            SearchKind.Note, SearchKind.Journal -> onOpenNote(row.id)
            SearchKind.Task -> onOpenTask(row.id)
            SearchKind.Goal -> onOpenGoal(row.id)
            SearchKind.Habit -> onOpenHabit(row.id)
        }
    }
}

/** Web `NotesSearchScreen`. */
@Composable
fun NotesSearchScreen(state: NotesSearchUiState, onAction: (NotesSearchAction) -> Unit, onBack: () -> Unit, onOpen: (SearchRowUi) -> Unit) {
    val c = Mitra.colors
    val q = state.query.trim()
    val total = state.counts[SearchScope.All] ?: 0
    Column(Modifier.fillMaxSize().imePadding()) {
        MitraTopBar("Search notes", onBack = onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = MitraDimens.pad, end = MitraDimens.pad, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(MitraDimens.gap),
        ) {
            item(key = "q") {
                MitraTextField(
                    state.query, { onAction(NotesSearchAction.Query(it)) }, "Search",
                    placeholder = "Search notes, tasks, goals, habits…",
                )
            }
            item(key = "scopes") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(SearchScope.entries) { s ->
                        MitraChip(
                            if (q.isEmpty()) s.name else "${s.name} ${state.counts[s] ?: 0}",
                            selected = state.scope == s,
                            onClick = { onAction(NotesSearchAction.Scope(s)) },
                        )
                    }
                }
            }
            when {
                q.isEmpty() -> item(key = "idle") {
                    MitraCard { EmptyState(MitraIcons.Search, "Search everything", "Notes first, then tasks, goals and habits — one timeline, not silos.") }
                }
                total == 0 -> item(key = "none") {
                    MitraCard {
                        EmptyState(
                            MitraIcons.Note, "No results for “$q”", "",
                            action = { MitraButton("Create note “$q”", onClick = { onAction(NotesSearchAction.CreateFromQuery) }, icon = MitraIcons.Plus) },
                        )
                    }
                }
                else -> item(key = "rows") {
                    MitraCard(contentPadding = PaddingValues(0.dp)) {
                        state.rows.forEachIndexed { i, row ->
                            if (i > 0) HorizontalDivider(color = c.border)
                            ResultRow(row) { onOpen(row) }
                        }
                    }
                }
            }
            val back = state.backlinksFor
            if (back != null) {
                item(key = "bl-h") { SectionHeader("Backlinks for “$back”") }
                items(state.backlinks, key = { "bl:${it.id}" }) { NoteCard(it) { onOpen(SearchRowUi(SearchKind.Note, it.id, emptyList(), emptyList())) } }
            }
        }
    }
}

@Composable
private fun ResultRow(row: SearchRowUi, onClick: () -> Unit) {
    val c = Mitra.colors
    val icon = when (row.kind) {
        SearchKind.Note -> MitraIcons.Note
        SearchKind.Journal -> MitraIcons.Edit
        SearchKind.Task -> MitraIcons.CheckSquare
        SearchKind.Goal -> MitraIcons.Target
        SearchKind.Habit -> MitraIcons.Repeat
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = c.muted)
        Column(Modifier.weight(1f)) {
            Text(marked(row.title, c.accentSoft), style = Mitra.type.bodyBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(marked(row.sub, c.accentSoft), style = Mitra.type.xs, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun marked(runs: Marked, bg: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    runs.forEach { (t, hit) -> if (hit) withStyle(SpanStyle(background = bg, fontWeight = FontWeight.Bold)) { append(t) } else append(t) }
}
