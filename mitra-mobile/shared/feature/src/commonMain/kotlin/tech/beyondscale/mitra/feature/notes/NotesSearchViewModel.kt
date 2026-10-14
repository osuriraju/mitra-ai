package tech.beyondscale.mitra.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.Note
import tech.beyondscale.mitra.domain.model.NoteDraft
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.notes.highlight
import tech.beyondscale.mitra.domain.notes.noteSearch
import tech.beyondscale.mitra.domain.notes.searchBacklinks
import tech.beyondscale.mitra.domain.notes.searchSnippet
import tech.beyondscale.mitra.domain.repository.GoalsRepository
import tech.beyondscale.mitra.domain.repository.HabitsRepository
import tech.beyondscale.mitra.domain.repository.NotesRepository
import tech.beyondscale.mitra.domain.repository.TasksRepository

enum class SearchScope { All, Notes, Tasks, Goals, Habits }
enum class SearchKind { Note, Journal, Task, Goal, Habit }

/** A highlighted run: (text, isMatch). */
typealias Marked = List<Pair<String, Boolean>>

data class SearchRowUi(val kind: SearchKind, val id: String, val title: Marked, val sub: Marked)

data class NotesSearchUiState(
    val query: String = "",
    val scope: SearchScope = SearchScope.All,
    /** Per scope, in [SearchScope] order. */
    val counts: Map<SearchScope, Int> = emptyMap(),
    val rows: List<SearchRowUi> = emptyList(),
    /** "Backlinks for “X”" with the notes linking to it; null when none. */
    val backlinksFor: String? = null,
    val backlinks: List<NoteRowUi> = emptyList(),
)

sealed interface NotesSearchAction {
    data class Query(val q: String) : NotesSearchAction
    data class Scope(val scope: SearchScope) : NotesSearchAction
    /** "Create note “q”" from the no-results state. */
    data object CreateFromQuery : NotesSearchAction
}

/** Web `NotesSearchScreen`: notes first, then tasks, goals and habits, with matches highlighted. */
class NotesSearchViewModel(
    private val notes: NotesRepository,
    tasks: TasksRepository,
    goals: GoalsRepository,
    habits: HabitsRepository,
    clock: AppClock,
) : ViewModel() {
    private val open = Channel<String>(Channel.BUFFERED)
    /** Note ids to navigate to (after "Create note"). */
    val openNote: Flow<String> = open.receiveAsFlow()

    private data class Local(val q: String = "", val scope: SearchScope = SearchScope.All)
    private val local = MutableStateFlow(Local())

    private data class Inputs(val notes: List<Note>, val tasks: List<Task>, val goals: List<Goal>, val habits: List<Habit>)

    val state: StateFlow<NotesSearchUiState> =
        combine(combine(notes.notes, tasks.tasks, goals.goals, habits.habits, ::Inputs), clock.dayFlow(), local, ::build)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesSearchUiState())

    fun onAction(a: NotesSearchAction) {
        when (a) {
            is NotesSearchAction.Query -> local.update { it.copy(q = a.q) }
            is NotesSearchAction.Scope -> local.update { it.copy(scope = a.scope) }
            NotesSearchAction.CreateFromQuery -> {
                val q = local.value.q.trim()
                if (q.isEmpty()) return
                viewModelScope.launch { open.trySend(notes.create(NoteDraft(title = q)).id) }
            }
        }
    }

    private fun build(i: Inputs, today: String, l: Local): NotesSearchUiState {
        val q = l.q.trim()
        val r = noteSearch(i.notes, i.tasks, i.goals, i.habits, q)
        fun m(t: String) = highlight(t, q)
        fun plain(t: String): Marked = listOf(t to false)
        val rows = buildList {
            if (l.scope == SearchScope.All || l.scope == SearchScope.Notes) r.notes.forEach { n ->
                val j = n.journalDate != null
                add(SearchRowUi(if (j) SearchKind.Journal else SearchKind.Note, n.id, m(n.title), plain("${if (j) "Journal" else "Note"} · ${n.folder} · ") + m(searchSnippet(n.body, q))))
            }
            if (l.scope == SearchScope.All || l.scope == SearchScope.Tasks) r.tasks.forEach { t ->
                val due = t.due
                add(SearchRowUi(SearchKind.Task, t.id, m(t.title), plain("Task · ${if (t.done) "done" else if (due != null) fmtDay(due, today) else "Inbox"}")))
            }
            if (l.scope == SearchScope.All || l.scope == SearchScope.Goals) r.goals.forEach { g ->
                add(SearchRowUi(SearchKind.Goal, g.id, m(g.name), plain("Goal · ${g.state.name.lowercase()}")))
            }
            if (l.scope == SearchScope.All || l.scope == SearchScope.Habits) r.habits.forEach { h ->
                add(SearchRowUi(SearchKind.Habit, h.id, m(h.name), plain("Habit")))
            }
        }
        val back = searchBacklinks(i.notes, q)?.takeIf { it.second.isNotEmpty() }
        return NotesSearchUiState(
            query = l.q,
            scope = l.scope,
            counts = mapOf(
                SearchScope.All to r.total, SearchScope.Notes to r.notes.size, SearchScope.Tasks to r.tasks.size,
                SearchScope.Goals to r.goals.size, SearchScope.Habits to r.habits.size,
            ),
            rows = rows,
            backlinksFor = back?.first?.title,
            backlinks = back?.second.orEmpty().map { it.toRowUi(today) },
        )
    }
}
