package tech.beyondscale.mitra.domain.tasks

import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.dowMon
import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.Project

enum class ParsedField { Due, Pri, Project }

data class ParsedTask(val title: String, val due: String? = null, val pri: Priority? = null, val project: Project? = null)

/**
 * Spec §9 Quick Add parsing for tasks: whole words `today`, `tomorrow`, weekday names, `p1`–`p3` and `#project`
 * become due / priority / project and are removed from the title. Matching is case-insensitive. Fields in [ignore]
 * (chips the user dismissed) are left in the title as plain words. Later tokens win.
 */
object QuickAddParser {
    private val WEEKDAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

    fun parse(input: String, today: String, projects: List<Project>, ignore: Set<ParsedField> = emptySet()): ParsedTask {
        var due: String? = null
        var pri: Priority? = null
        var project: Project? = null
        val kept = mutableListOf<String>()
        for (word in input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val w = word.lowercase()
            val asDue = if (ParsedField.Due in ignore) null else when (w) {
                "today" -> today
                "tomorrow" -> addDays(today, 1)
                in WEEKDAYS -> nextWeekday(today, WEEKDAYS.indexOf(w))
                else -> null
            }
            val asPri = if (ParsedField.Pri in ignore) null else when (w) {
                "p1" -> Priority.High
                "p2" -> Priority.Med
                "p3" -> Priority.Low
                else -> null
            }
            val asProject = if (ParsedField.Project in ignore || !w.startsWith("#")) null else matchProject(w.drop(1), projects)
            when {
                asDue != null -> due = asDue
                asPri != null -> pri = asPri
                asProject != null -> project = asProject
                else -> kept += word
            }
        }
        return ParsedTask(kept.joinToString(" "), due, pri, project)
    }

    /** 1–7 days ahead: a weekday typed on that same weekday means next week's. */
    internal fun nextWeekday(today: String, targetMon0: Int): String {
        val diff = (targetMon0 - dowMon(today) + 7) % 7
        return addDays(today, if (diff == 0) 7 else diff)
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Exact match on the normalised name ("#deepwork" → "Deep work"), else a unique prefix. */
    internal fun matchProject(tag: String, projects: List<Project>): Project? {
        val k = norm(tag)
        if (k.isEmpty()) return null
        return projects.firstOrNull { norm(it.name) == k } ?: projects.filter { norm(it.name).startsWith(k) }.singleOrNull()
    }
}
