package tech.beyondscale.mitra.domain.search

import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.Modules
import tech.beyondscale.mitra.domain.model.Note
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.Transaction

/** Screens the search can jump to (web palette's NAV + "Go to" rows); [module] gates it on that module being on. */
enum class SearchPlace(val label: String, val module: String? = null) {
    Today("Today"), Tasks("Tasks"), Focus("Focus mode"),
    Money("Money", "money"), Transactions("Transactions", "money"), Budgets("Budgets", "money"),
    Recurring("Recurring payments", "money"), Subscriptions("Subscriptions", "money"),
    Habits("Habits"), Goals("Goals"), GoalReview("Goal review"),
    Wellness("Wellness check-in", "wellness"), Notes("Notes"), Journal("Journal"),
    Ai("Ask Mitra", "ai"), AiActivity("AI activity", "ai"),
    Settings("Settings"), Profile("Profile");

    fun enabled(m: Modules): Boolean = when (module) {
        "money" -> m.money
        "wellness" -> m.wellness
        "ai" -> m.ai
        else -> true
    }
}

data class GlobalSearchResult(
    val tasks: List<Task> = emptyList(),
    val notes: List<Note> = emptyList(),
    val transactions: List<Transaction> = emptyList(),
    val goals: List<Goal> = emptyList(),
    val habits: List<Habit> = emptyList(),
    val places: List<SearchPlace> = emptyList(),
) {
    /** Records matched (places excluded) — zero means "No matching records". */
    val records: Int get() = tasks.size + notes.size + transactions.size + goals.size + habits.size
}

/** Per-group caps, as the web ⌘K palette (tasks 4, notes 3, money 2, goals 2, habits 2). */
object SearchCaps { const val TASKS = 4; const val NOTES = 3; const val MONEY = 2; const val GOALS = 2; const val HABITS = 2 }

/**
 * How well [text] matches [k] (already lower-cased): 0 starts with it, 1 a word starts with it, 2 contains it,
 * null no match. Lower ranks first.
 */
fun matchRank(text: String, k: String): Int? {
    val t = text.lowercase()
    val i = t.indexOf(k)
    return when {
        i < 0 -> null
        i == 0 -> 0
        Regex("(^|[^\\p{L}\\p{N}])" + Regex.escape(k)).containsMatchIn(t) -> 1
        else -> 2
    }
}

/**
 * The global search (web `CommandScreen`) over the local data: case-insensitive, ranked (prefix, then word start,
 * then substring; ties keep the repository order), capped per group. Money is searched only with the module on.
 * A blank query matches no records and lists every enabled place (the palette's idle "Go to" list).
 */
fun globalSearch(
    q: String,
    tasks: List<Task>,
    notes: List<Note>,
    transactions: List<Transaction>,
    goals: List<Goal>,
    habits: List<Habit>,
    modules: Modules,
): GlobalSearchResult {
    val k = q.trim().lowercase()
    val places = SearchPlace.entries.filter { it.enabled(modules) }
    if (k.isEmpty()) return GlobalSearchResult(places = places)
    fun <T> ranked(items: List<T>, cap: Int, rank: (T) -> Int?, then: Comparator<T>? = null): List<T> {
        val hits = items.mapNotNull { item -> rank(item)?.let { item to it } }
        val byRank = compareBy<Pair<T, Int>> { it.second }
        val cmp = if (then == null) byRank else byRank.thenBy(then) { it.first }
        return hits.sortedWith(cmp).take(cap).map { it.first }
    }
    return GlobalSearchResult(
        // Open tasks before done ones of the same rank.
        tasks = ranked(tasks, SearchCaps.TASKS, { matchRank(it.title, k) }, compareBy { it.done }),
        // A body / tag hit ranks after any title hit.
        notes = ranked(notes, SearchCaps.NOTES, { n ->
            matchRank(n.title, k) ?: if ((n.body + " " + n.tags.joinToString(" ")).lowercase().contains(k)) 3 else null
        }),
        // Most recent first within a rank.
        transactions = if (!modules.money) emptyList() else
            ranked(transactions, SearchCaps.MONEY, { matchRank(it.merchant, k) }, compareByDescending<Transaction> { it.date + it.time }),
        goals = ranked(goals, SearchCaps.GOALS, { matchRank(it.name, k) }),
        habits = ranked(habits, SearchCaps.HABITS, { matchRank(it.name, k) }),
        places = places.filter { it.label.lowercase().contains(k) },
    )
}
