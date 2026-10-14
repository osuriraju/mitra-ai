package tech.beyondscale.mitra.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.core.inrPaise
import tech.beyondscale.mitra.domain.goals.goalProgress
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.Note
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.TaskDraft
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.domain.notes.highlight
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.GoalsRepository
import tech.beyondscale.mitra.domain.repository.HabitsRepository
import tech.beyondscale.mitra.domain.repository.MoneyRepository
import tech.beyondscale.mitra.domain.repository.NotesRepository
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.domain.search.SearchPlace
import tech.beyondscale.mitra.domain.search.globalSearch
import tech.beyondscale.mitra.feature.notes.Marked

/** Where a result row leads; the shell maps each to a route. */
sealed interface SearchTarget {
    data class OpenTask(val id: String) : SearchTarget
    data class OpenNote(val id: String) : SearchTarget
    data class OpenTransaction(val id: String) : SearchTarget
    data class OpenGoal(val id: String) : SearchTarget
    data class OpenHabit(val id: String) : SearchTarget
    data class GoTo(val place: SearchPlace) : SearchTarget
    /** "Ask Mitra: “q”" — opens the AI chat with [q] sent. */
    data class AskAi(val q: String) : SearchTarget
}

enum class SearchIcon { AddTask, Ask, Task, Note, Money, Goal, Habit, Place }

data class SearchItemUi(val key: String, val icon: SearchIcon, val title: Marked, val sub: String, val target: SearchTarget?)

data class SearchGroupUi(val title: String, val items: List<SearchItemUi>)

data class SearchUiState(
    val query: String = "",
    val groups: List<SearchGroupUi> = emptyList(),
    /** A query that matched no records (the quick actions still show). */
    val noRecords: Boolean = false,
)

sealed interface SearchAction {
    data class Query(val q: String) : SearchAction
    data class Open(val item: SearchItemUi) : SearchAction
}

/** Web ⌘K `CommandScreen` over the local DB: quick actions, then tasks, notes, money, goals, habits and places. */
class SearchViewModel(
    private val tasks: TasksRepository,
    notes: NotesRepository,
    money: MoneyRepository,
    goals: GoalsRepository,
    habits: HabitsRepository,
    auth: AuthRepository,
    clock: AppClock,
) : ViewModel() {
    private val nav = Channel<SearchTarget>(Channel.BUFFERED)
    val navigation: Flow<SearchTarget> = nav.receiveAsFlow()
    private val query = MutableStateFlow("")
    private var adding = false

    private data class Data(val tasks: List<Task>, val notes: List<Note>, val txs: List<Transaction>, val goals: List<Goal>, val habits: List<Habit>)

    private val modules: Flow<Modules> = auth.session.map { (it as? SessionState.SignedIn)?.user?.settings?.modules ?: Modules() }
    val state: StateFlow<SearchUiState> = combine(
        combine(tasks.tasks, notes.notes, money.transactions, goals.goals, habits.habits, ::Data), modules, clock.dayFlow(), query, ::build,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun onAction(a: SearchAction) {
        when (a) {
            is SearchAction.Query -> query.value = a.q
            is SearchAction.Open -> {
                val t = a.item.target
                if (t != null) { nav.trySend(t); return }
                // The only row without a target is "Add task “q”".
                val title = query.value.trim()
                if (title.isEmpty() || adding) return
                adding = true
                viewModelScope.launch {
                    try { nav.send(SearchTarget.OpenTask(tasks.create(TaskDraft(title)).id)) } finally { adding = false }
                }
            }
        }
    }

    private fun build(d: Data, modules: Modules, today: String, raw: String): SearchUiState {
        val q = raw.trim()
        val r = globalSearch(q, d.tasks, d.notes, d.txs, d.goals, d.habits, modules)
        fun m(t: String): Marked = highlight(t, q)
        fun day(iso: String) = if (iso.length >= 10) fmtDay(iso.take(10), today) else ""
        val groups = buildList {
            if (q.isNotEmpty()) add(
                SearchGroupUi("Actions", buildList {
                    add(SearchItemUi("add", SearchIcon.AddTask, listOf("Add task “$q”" to false), "Creates it in your inbox", null))
                    if (modules.ai) add(SearchItemUi("ask", SearchIcon.Ask, listOf("Ask Mitra: “$q”" to false), "Natural-language capture or question", SearchTarget.AskAi(q)))
                }),
            )
            fun group(title: String, items: List<SearchItemUi>) { if (items.isNotEmpty()) add(SearchGroupUi(title, items)) }
            group("Tasks", r.tasks.map { t ->
                val due = t.due
                SearchItemUi("t:${t.id}", SearchIcon.Task, m(t.title), "Task · ${if (t.done) "done" else if (due != null) fmtDay(due, today) else "Inbox"}", SearchTarget.OpenTask(t.id))
            })
            group("Notes", r.notes.map { n ->
                SearchItemUi("n:${n.id}", SearchIcon.Note, m(n.title), listOf(if (n.journalDate != null) "Journal" else "Note", n.folder, day(n.updatedAt)).filter { it.isNotEmpty() }.joinToString(" · "), SearchTarget.OpenNote(n.id))
            })
            group("Money", r.transactions.map { t ->
                val kind = when (t.kind) { TxKind.Expense -> "Expense"; TxKind.Income -> "Income"; TxKind.Transfer -> "Transfer" }
                SearchItemUi("x:${t.id}", SearchIcon.Money, m(t.merchant) + listOf(" · ${inrPaise(t.amountPaise)}" to false), "$kind · ${day(t.date)}", SearchTarget.OpenTransaction(t.id))
            })
            group("Goals", r.goals.map { g ->
                SearchItemUi("g:${g.id}", SearchIcon.Goal, m(g.name), "Goal · ${goalProgress(g, d.habits, today)}%", SearchTarget.OpenGoal(g.id))
            })
            group("Habits", r.habits.map { h -> SearchItemUi("h:${h.id}", SearchIcon.Habit, m(h.name), "Habit", SearchTarget.OpenHabit(h.id)) })
            group("Go to", r.places.map { p -> SearchItemUi("p:${p.name}", SearchIcon.Place, m(p.label), "Go to", SearchTarget.GoTo(p)) })
        }
        return SearchUiState(raw, groups, noRecords = q.isNotEmpty() && r.records == 0)
    }
}
