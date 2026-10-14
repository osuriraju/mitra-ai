package tech.beyondscale.mitra.domain.tasks

import tech.beyondscale.mitra.domain.model.Priority
import tech.beyondscale.mitra.domain.model.Project
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuickAddParserTest {
    private val today = "2026-10-14" // Wednesday
    private val work = Project("p1", "Work", "💼")
    private val deep = Project("p2", "Deep work", "🎯")
    private val design = Project("p3", "Design", "🎨")
    private val side = Project("p4", "Side project", "🚀")
    private val projects = listOf(work, deep, design, side)

    private fun parse(s: String, ignore: Set<ParsedField> = emptySet()) = QuickAddParser.parse(s, today, projects, ignore)

    @Test fun parsesAllTokens() =
        assertEquals(ParsedTask("Call John", "2026-10-15", Priority.High, work), parse("Call John tomorrow p1 #work"))

    @Test fun todayAndCaseInsensitive() {
        assertEquals(ParsedTask("Pay rent", today), parse("Pay rent today"))
        assertEquals(ParsedTask("Ship it", "2026-10-15", Priority.Med), parse("Ship it TOMORROW P2"))
        assertEquals(Priority.Low, parse("Stretch p3").pri)
    }

    @Test fun weekdayMeansTheNextOneWithinAWeek() {
        assertEquals("2026-10-16", parse("Gym friday").due)
        assertEquals("2026-10-19", parse("Plan monday").due)
        assertEquals("2026-10-21", parse("Review wednesday").due) // typed on a Wednesday → next week
    }

    @Test fun projectTagsMatchNormalisedNamesOrAUniquePrefix() {
        assertEquals(deep, parse("Outline #deepwork").project)
        assertEquals(side, parse("Fix bug #side-project").project)
        assertEquals(deep, parse("Outline #deep").project)
        assertEquals(ParsedTask("Sketch #d"), parse("Sketch #d")) // ambiguous: Deep work / Design
        assertEquals(ParsedTask("Read #novel"), parse("Read #novel")) // unknown tag stays in the title
    }

    @Test fun onlyWholeWordsCount() {
        assertEquals(ParsedTask("Prep today's standup"), parse("Prep today's standup"))
        assertEquals(ParsedTask("Buy p10 cable"), parse("Buy p10 cable"))
    }

    @Test fun ignoredFieldsStayInTheTitle() {
        val r = parse("Buy milk tomorrow p1", ignore = setOf(ParsedField.Due))
        assertEquals("Buy milk tomorrow", r.title)
        assertNull(r.due)
        assertEquals(Priority.High, r.pri)
    }

    @Test fun collapsesWhitespaceAndMayLeaveAnEmptyTitle() {
        assertEquals(ParsedTask("Call John"), parse("  Call   John  "))
        assertEquals(ParsedTask("", today, Priority.High), parse("today p1"))
    }
}
