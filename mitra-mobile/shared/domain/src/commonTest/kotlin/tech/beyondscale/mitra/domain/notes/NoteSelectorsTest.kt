package tech.beyondscale.mitra.domain.notes

import tech.beyondscale.mitra.domain.goals.goal
import tech.beyondscale.mitra.domain.habits.habit
import tech.beyondscale.mitra.domain.model.Note
import tech.beyondscale.mitra.domain.model.NoteChange
import tech.beyondscale.mitra.domain.model.NoteDraft
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.domain.model.WellnessEntry
import tech.beyondscale.mitra.domain.model.applyChanges
import tech.beyondscale.mitra.domain.model.toNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val TODAY = "2026-10-14"

class NoteSelectorsTest {
    private fun note(id: String, title: String = id, body: String = "", folder: String = "Personal", tags: List<String> = emptyList(), updatedAt: String = TODAY, journalDate: String? = null) =
        Note(id, title, body, folder, tags, journalDate, updatedAt = updatedAt, createdAt = "2026-10-01")

    @Test fun excerptStripsMarkdown() {
        assertEquals("Plan\n• buy milk\nsee Budget", excerpt("## Plan\n- [ ] buy milk\nsee [[Budget]]"))
        assertEquals(180, excerpt("x".repeat(300)).length)
    }

    @Test fun folderFilterSortsByUpdated() {
        val ns = listOf(note("a", folder = "Work", updatedAt = "2026-10-01"), note("b", folder = "Ideas", updatedAt = "2026-10-12"), note("c", folder = "Work", updatedAt = "2026-10-10"))
        assertEquals(listOf("b", "c", "a"), notesInFolder(ns, "All").map { it.id })
        assertEquals(listOf("c", "a"), notesInFolder(ns, "Work").map { it.id })
        assertEquals(listOf("Work" to 2, "Ideas" to 1, "Learning" to 0, "Journal" to 0, "Personal" to 0), folderCounts(ns))
    }

    @Test fun tags() {
        val ns = listOf(note("a", tags = listOf("x", "y")), note("b", tags = listOf("y")))
        assertEquals(listOf("y" to 2, "x" to 1), tagCounts(ns))
        assertEquals(listOf("ideas", "money", "focus"), parseTags("ideas, #money  focus,"))
        assertEquals(listOf("a", "b"), addTag(listOf("a", "b"), " #b "))
        assertEquals(listOf("a", "c"), addTag(listOf("a"), "c"))
    }

    @Test fun searchAcrossModules() {
        val ns = listOf(note("a", "Budget", body = "monthly plan"), note("b", "Ideas", tags = listOf("budget")), note("c", "Other"))
        val tasks = listOf(Task("t1", "Review budget"), Task("t2", "Call"))
        val r = noteSearch(ns, tasks, listOf(goal(name = "Budget buffer")), listOf(habit(name = "Read")), "  BUDGET ")
        assertEquals(listOf("a", "b"), r.notes.map { it.id })
        assertEquals(listOf("t1"), r.tasks.map { it.id })
        assertEquals(1, r.goals.size)
        assertEquals(0, r.habits.size)
        assertEquals(4, r.total)
        assertEquals(0, noteSearch(ns, tasks, emptyList(), emptyList(), " ").total)
    }

    @Test fun snippetAndHighlight() {
        val body = "a".repeat(50) + "\nNeedle here" + "b".repeat(80)
        val s = searchSnippet(body, "needle")
        assertEquals("…" + "a".repeat(39) + " Needle here" + "b".repeat(49) + "…", s)
        assertEquals("plain", searchSnippet("# plain", "zzz"))
        assertEquals(listOf("The " to false, "cat" to true, " and " to false, "CAT" to true), highlight("The cat and CAT", "cat"))
        assertEquals(listOf("a.b" to true), highlight("a.b", "a.b"))
        assertEquals(listOf("x" to false), highlight("x", ""))
    }

    @Test fun bodyLinesAndChecklist() {
        val body = "# T\n## S\n- [ ] a\n- [x] b\n- c\n\ntext"
        assertEquals(
            listOf(NoteLine.H1("T"), NoteLine.H2("S"), NoteLine.Check(false, "a"), NoteLine.Check(true, "b"), NoteLine.Bullet("c"), NoteLine.Blank, NoteLine.Para("text")),
            parseNoteBody(body),
        )
        assertEquals("# T\n## S\n- [x] a\n- [x] b\n- c\n\ntext", toggleChecklistLine(body, 2))
        assertEquals("# T\n## S\n- [ ] a\n- [ ] b\n- c\n\ntext", toggleChecklistLine(body, 3))
        assertEquals(body, toggleChecklistLine(body, 99))
        assertEquals("a", firstUnchecked(body))
        assertNull(firstUnchecked("- [x] done"))
        assertEquals("x\n- [ ] ", insertToken("x", "- [ ] "))
        assertEquals("# ", insertToken("", "# "))
        assertEquals("x\n[[", insertToken("x\n", "[["))
        assertEquals(3, wordCount("  one two\nthree "))
    }

    @Test fun wikiLinksAndBacklinks() {
        assertEquals(
            listOf(WikiSegment("see ", false), WikiSegment("Budget", true), WikiSegment(" and ", false), WikiSegment("Trip", true)),
            wikiSegments("see [[Budget]] and [[Trip]]"),
        )
        val a = note("a", "Budget")
        val b = note("b", "Plan", body = "links [[Budget]]")
        val ns = listOf(a, b)
        assertEquals(listOf(b), backlinks(ns, a))
        assertEquals(a to listOf(b), searchBacklinks(ns, "budg"))
        assertNull(searchBacklinks(ns, ""))
        val g = goal(id = "g1", name = "Trip")
        assertEquals(WikiTarget.ToNote("a"), resolveWikiLink("Budget", ns, listOf(g), emptyList()))
        assertEquals(WikiTarget.ToGoal("g1"), resolveWikiLink("Trip", ns, listOf(g), emptyList()))
        assertEquals(WikiTarget.ToTask("t1"), resolveWikiLink("Call", ns, emptyList(), listOf(Task("t1", "Call"))))
        assertNull(resolveWikiLink("Nope", ns, emptyList(), emptyList()))
    }

    @Test fun linkedPanel() {
        val n = note("n", "Notes on emergency fund", body = "see [[Call bank]] for Side Project", tags = listOf("home-reno"))
        val l = noteLinks(
            n,
            listOf(goal(id = "g1", name = "Build emergency fund"), goal(id = "g2", name = "Run 10k")),
            listOf(Task("t1", "Call bank"), Task("t2", "Other")),
            listOf(Project("p1", "Home Reno"), Project("p2", "Side Project"), Project("p3", "None")),
        )
        assertEquals(listOf("g1"), l.goals.map { it.id })
        assertEquals(listOf("t1"), l.tasks.map { it.id })
        assertEquals(listOf("p1", "p2"), l.projects.map { it.id })
    }

    @Test fun journal() {
        val ns = listOf(
            note("j1", journalDate = "2026-10-14"), note("j2", journalDate = "2026-10-10"), note("j3", journalDate = "2026-10-12"), note("n"),
        )
        assertEquals("j1", journalFor(ns, TODAY)?.id)
        assertEquals(listOf("j3", "j2"), earlierJournal(ns, TODAY).map { it.id })
        assertEquals(listOf("2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15", "2026-10-16", "2026-10-17", "2026-10-18"), journalWeek(TODAY))
        assertEquals("Journal — Wed 14 Oct", journalTitle(TODAY))
    }

    @Test fun localDraft() {
        val tasks = listOf(
            Task("t1", "Write report", done = true, completedAt = TODAY),
            Task("t2", "Call John", due = TODAY, time = "18:00"),
            Task("t3", "Gym", due = TODAY),
        )
        val txs = listOf(
            Transaction("x1", TxKind.Expense, 45000, null, "a1", null, "Swiggy", null, TODAY, "13:00"),
            Transaction("x2", TxKind.Expense, 5050, null, "a1", null, "", null, TODAY, "14:00"),
            Transaction("x3", TxKind.Income, 100000, null, "a1", null, "Pay", null, TODAY, "09:00"),
        )
        val d = localJournalDraft(
            TODAY, WellnessEntry(TODAY, sleepStart = "00:00", sleepEnd = "06:00"), 450, tasks,
            listOf(habit(name = "Read", logs = mapOf(TODAY to 1)), habit(id = "h2", name = "Walk")), txs, 4,
        )
        assertEquals(
            "**Morning.** Slept 6h 00m — under target. read done. Finished 1 task including “Write report”.\n\n" +
                "**Money.** ₹500.5 so far: Swiggy, expense.\n\n" +
                "**Still open.** Call John (18:00), Gym.",
            d.text,
        )
        assertEquals("4 activities, 2 expenses, 1 check-in", d.sources)
        val empty = localJournalDraft(TODAY, null, 450, emptyList(), emptyList(), emptyList(), 0)
        assertEquals("**Morning.** No habits ticked yet. \n\n**Money.** Nothing spent yet.\n\n**Still open.** Nothing left for today.", empty.text)
        assertEquals("0 activities, 0 expenses, 0 check-in", empty.sources)
    }

    @Test fun draftsAndChanges() {
        val n = NoteDraft("  Idea ", folder = "Ideas").toNote("n1", TODAY)
        assertEquals(Note("n1", "Idea", folder = "Ideas", createdAt = TODAY, updatedAt = TODAY), n)
        val e = n.applyChanges(listOf(NoteChange.Body("x"), NoteChange.Mood(2), NoteChange.JournalDate(TODAY)), "2026-10-15")
        assertEquals("2026-10-15", e.updatedAt)
        assertEquals("x", e.body)
        assertEquals(2, e.mood)
        assertEquals(TODAY, e.journalDate)
    }
}
