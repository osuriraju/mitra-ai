package tech.beyondscale.mitra.domain.notes

import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.dowMon
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.core.localeIN
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.Habit
import tech.beyondscale.mitra.domain.model.JournalDraft
import tech.beyondscale.mitra.domain.model.NOTE_FOLDERS
import tech.beyondscale.mitra.domain.model.Note
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.domain.model.WellnessEntry
import tech.beyondscale.mitra.domain.wellness.hm
import tech.beyondscale.mitra.domain.wellness.sleepMinutes

/* Ports of mitra-frontend/src/store/selectors.ts `noteSearch` and the helpers in screens/notes.tsx. */

private val HEADING_MARKS = Regex("(?m)^#+\\s*")
private val WIKI_BRACKETS = Regex("\\[\\[|]]")
private val CHECKBOX = Regex("- \\[[ x]] ")
private val WIKI_LINK = Regex("\\[\\[[^\\]]+]]")
private val WHITESPACE = Regex("\\s+")
private val TAG_SPLIT = Regex("[,\\s]+")

/** Card/list preview: markdown marks stripped, checkboxes as bullets, 180 chars (web `excerpt`). */
fun excerpt(body: String): String =
    body.replace(HEADING_MARKS, "").replace(WIKI_BRACKETS, "").replace(CHECKBOX, "• ").take(180)

/** All notes or one folder's, most recently edited first (web `NotesScreen`). */
fun notesInFolder(notes: List<Note>, folder: String): List<Note> =
    notes.filter { folder == "All" || it.folder == folder }.sortedByDescending { it.updatedAt }

/** Count per [NOTE_FOLDERS] entry, in that order. */
fun folderCounts(notes: List<Note>): List<Pair<String, Int>> = NOTE_FOLDERS.map { f -> f to notes.count { it.folder == f } }

/** Tag → count, most used first (ties keep first-seen order). */
fun tagCounts(notes: List<Note>): List<Pair<String, Int>> {
    val m = LinkedHashMap<String, Int>()
    notes.forEach { n -> n.tags.forEach { t -> m[t] = (m[t] ?: 0) + 1 } }
    return m.entries.map { it.key to it.value }.sortedByDescending { it.second }
}

/** "ideas, #money  focus" → ["ideas", "money", "focus"] (new-note form). */
fun parseTags(input: String): List<String> = input.split(TAG_SPLIT).map { it.removePrefix("#").trim() }.filter { it.isNotEmpty() }

/** Adds one typed tag (leading # dropped), de-duplicated (editor tag field). */
fun addTag(tags: List<String>, tag: String): List<String> = (tags + tag.trim().removePrefix("#")).distinct()

data class NoteSearchResult(val notes: List<Note>, val tasks: List<Task>, val goals: List<Goal>, val habits: List<Habit>) {
    val total: Int get() = notes.size + tasks.size + goals.size + habits.size
}

/** Case-insensitive substring search; notes match title, body or tags. Blank query = nothing (web `noteSearch`). */
fun noteSearch(notes: List<Note>, tasks: List<Task>, goals: List<Goal>, habits: List<Habit>, q: String): NoteSearchResult {
    val k = q.trim().lowercase()
    if (k.isEmpty()) return NoteSearchResult(emptyList(), emptyList(), emptyList(), emptyList())
    return NoteSearchResult(
        notes = notes.filter { (it.title + " " + it.body + " " + it.tags.joinToString(" ")).lowercase().contains(k) },
        tasks = tasks.filter { it.title.lowercase().contains(k) },
        goals = goals.filter { it.name.lowercase().contains(k) },
        habits = habits.filter { it.name.lowercase().contains(k) },
    )
}

/** Body context around the first match, or the excerpt's first 80 chars (web search `snippet`). */
fun searchSnippet(body: String, q: String): String {
    val i = body.lowercase().indexOf(q.lowercase())
    if (i < 0) return excerpt(body).take(80)
    return "…" + body.substring(maxOf(0, i - 40), minOf(body.length, i + 60)).replace('\n', ' ') + "…"
}

/** [text] split into (segment, isMatch) runs for highlighting [q] case-insensitively (web search `mark`). */
fun highlight(text: String, q: String): List<Pair<String, Boolean>> {
    if (q.isEmpty()) return listOf(text to false)
    val out = mutableListOf<Pair<String, Boolean>>()
    var last = 0
    Regex(Regex.escape(q), RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
        if (m.range.first > last) out += text.substring(last, m.range.first) to false
        out += m.value to true
        last = m.range.last + 1
    }
    if (last < text.length) out += text.substring(last) to false
    return out
}

/** Other notes whose body links `[[note.title]]`. */
fun backlinks(notes: List<Note>, note: Note): List<Note> = notes.filter { it.id != note.id && it.body.contains("[[${note.title}]]") }

/** On the search screen: the first note whose title contains [q], with its backlinks (null when none). */
fun searchBacklinks(notes: List<Note>, q: String): Pair<Note, List<Note>>? {
    if (q.isEmpty()) return null
    val back = notes.firstOrNull { it.title.lowercase().contains(q.lowercase()) } ?: return null
    return back to backlinks(notes, back)
}

/** One rendered line of a note body (web `NoteBody`). */
sealed interface NoteLine {
    data class H1(val text: String) : NoteLine
    data class H2(val text: String) : NoteLine
    /** `- [ ] ` / `- [x] `; [text] has the 6-char marker removed. */
    data class Check(val done: Boolean, val text: String) : NoteLine
    data class Bullet(val text: String) : NoteLine
    data object Blank : NoteLine
    data class Para(val text: String) : NoteLine
}

fun parseNoteBody(body: String): List<NoteLine> = body.split("\n").map { l ->
    when {
        l.startsWith("## ") -> NoteLine.H2(l.substring(3))
        l.startsWith("# ") -> NoteLine.H1(l.substring(2))
        l.startsWith("- [") -> NoteLine.Check(l.startsWith("- [x]"), l.drop(6))
        l.startsWith("- ") -> NoteLine.Bullet(l.substring(2))
        l.isBlank() -> NoteLine.Blank
        else -> NoteLine.Para(l)
    }
}

/** Flips the checkbox on line [index] of [body] (web `NoteBody.toggle`). */
fun toggleChecklistLine(body: String, index: Int): String {
    val lines = body.split("\n").toMutableList()
    val l = lines.getOrNull(index) ?: return body
    lines[index] = if (l.startsWith("- [x]")) l.replaceFirst("- [x]", "- [ ]") else l.replaceFirst("- [ ]", "- [x]")
    return lines.joinToString("\n")
}

/** Toolbar insert: [token] on a new line at the end (web `insert`). Tokens: "# ", "## ", "- ", "- [ ] ", "[[". */
fun insertToken(body: String, token: String): String = body + (if (body.endsWith("\n") || body.isEmpty()) "" else "\n") + token

/** A run of text that is plain or a `[[wikilink]]` ([text] is the link name without brackets). */
data class WikiSegment(val text: String, val isLink: Boolean)

fun wikiSegments(text: String): List<WikiSegment> {
    val out = mutableListOf<WikiSegment>()
    var last = 0
    WIKI_LINK.findAll(text).forEach { m ->
        if (m.range.first > last) out += WikiSegment(text.substring(last, m.range.first), false)
        out += WikiSegment(m.value.substring(2, m.value.length - 2), true)
        last = m.range.last + 1
    }
    if (last < text.length) out += WikiSegment(text.substring(last), false)
    return out
}

sealed interface WikiTarget {
    data class ToNote(val id: String) : WikiTarget
    data class ToGoal(val id: String) : WikiTarget
    data class ToTask(val id: String) : WikiTarget
}

/** A note title wins over a goal name over a task title; null when nothing matches. */
fun resolveWikiLink(name: String, notes: List<Note>, goals: List<Goal>, tasks: List<Task>): WikiTarget? =
    notes.firstOrNull { it.title == name }?.let { WikiTarget.ToNote(it.id) }
        ?: goals.firstOrNull { it.name == name }?.let { WikiTarget.ToGoal(it.id) }
        ?: tasks.firstOrNull { it.title == name }?.let { WikiTarget.ToTask(it.id) }

data class NoteLinks(val goals: List<Goal>, val tasks: List<Task>, val projects: List<Project>) {
    val isEmpty: Boolean get() = goals.isEmpty() && tasks.isEmpty() && projects.isEmpty()
}

/** The editor's "Linked" panel (web `linked`). */
fun noteLinks(n: Note, goals: List<Goal>, tasks: List<Task>, projects: List<Project>): NoteLinks = NoteLinks(
    goals = goals.filter { g -> n.body.contains("[[${g.name}]]") || n.title.contains(g.name.split(" ").takeLast(2).joinToString(" ")) },
    tasks = tasks.filter { t -> n.body.contains("[[${t.title}]]") },
    projects = projects.filter { p -> n.tags.contains(p.name.lowercase().replace(WHITESPACE, "-")) || n.body.contains(p.name) },
)

fun wordCount(body: String): Int = body.split(WHITESPACE).count { it.isNotEmpty() }

/** The first open checklist item's text, for the "turn into a task" suggestion; null when none. */
fun firstUnchecked(body: String): String? = body.split("\n").firstOrNull { it.startsWith("- [ ] ") }?.drop(6)

/* ---------- journal ---------- */

fun journalFor(notes: List<Note>, day: String): Note? = notes.firstOrNull { it.journalDate == day }

/** Up to 5 other journal entries, newest day first. */
fun earlierJournal(notes: List<Note>, day: String): List<Note> =
    notes.filter { it.journalDate != null && it.journalDate != day }.sortedByDescending { it.journalDate }.take(5)

/** Monday..Sunday of [day]'s week. */
fun journalWeek(day: String): List<String> {
    val start = addDays(day, -dowMon(day))
    return (0 until 7).map { addDays(start, it) }
}

/** "Journal — Wed 14 Oct". */
fun journalTitle(day: String): String = "Journal — ${fmtDay(day, day, relative = false)}"

/**
 * The offline journal draft (web `draftFor`), used when AI is off or the request fails. Unlike the web (whose
 * `spentToday` only counts the device's today), "so far" sums [day]'s own expenses.
 */
fun localJournalDraft(
    day: String,
    entry: WellnessEntry?,
    sleepTarget: Int,
    tasks: List<Task>,
    habits: List<Habit>,
    transactions: List<Transaction>,
    activitiesOnDay: Int,
): JournalDraft {
    val sleep = if (entry == null) 0 else sleepMinutes(entry.sleepStart, entry.sleepEnd)
    val done = tasks.filter { it.completedAt == day }
    val open = tasks.filter { it.due == day && !it.done }
    val doneHabits = habits.filter { (it.logs[day] ?: 0) >= it.target }
    val txs = transactions.filter { it.date == day && it.kind == TxKind.Expense }
    val spent = txs.sumOf { it.amountPaise } / 100.0
    val p1 = buildString {
        append("**Morning.** ")
        if (sleep != 0) append("Slept ${hm(sleep)}${if (sleep < sleepTarget) " — under target" else ""}. ")
        append(if (doneHabits.isNotEmpty()) "${doneHabits.joinToString(", ") { it.name.lowercase() }} done. " else "No habits ticked yet. ")
        if (done.isNotEmpty()) append("Finished ${done.size} task${if (done.size > 1) "s" else ""} including “${done[0].title}”.")
    }
    val p2 = "**Money.** " +
        if (txs.isNotEmpty()) "₹${localeIN(spent)} so far: ${txs.joinToString(", ") { it.merchant.ifEmpty { "expense" } }}." else "Nothing spent yet."
    val p3 = "**Still open.** " +
        if (open.isNotEmpty()) open.joinToString(", ") { "${it.title}${if (it.time != null) " (${it.time})" else ""}" } + "." else "Nothing left for today."
    return JournalDraft(
        text = "$p1\n\n$p2\n\n$p3",
        sources = "$activitiesOnDay activities, ${txs.size} expense${if (txs.size == 1) "" else "s"}, ${if (entry != null) 1 else 0} check-in",
    )
}
