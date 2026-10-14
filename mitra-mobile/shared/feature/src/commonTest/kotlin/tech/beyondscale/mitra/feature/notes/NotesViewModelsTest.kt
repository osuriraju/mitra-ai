package tech.beyondscale.mitra.feature.notes

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.domain.model.Note
import tech.beyondscale.mitra.domain.model.NoteChange
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.model.WellnessEntry
import tech.beyondscale.mitra.domain.notes.NoteLine
import tech.beyondscale.mitra.domain.notes.WikiTarget
import tech.beyondscale.mitra.domain.repository.NotesRepository
import tech.beyondscale.mitra.feature.common.UiEvent
import tech.beyondscale.mitra.testing.FakeActivityRepository
import tech.beyondscale.mitra.testing.FakeAiRepository
import tech.beyondscale.mitra.testing.FakeGoalsRepository
import tech.beyondscale.mitra.testing.FakeHabitsRepository
import tech.beyondscale.mitra.testing.FakeMoneyRepository
import tech.beyondscale.mitra.testing.FakeNotesRepository
import tech.beyondscale.mitra.testing.FakeTasksRepository
import tech.beyondscale.mitra.testing.FakeWellnessRepository
import tech.beyondscale.mitra.testing.FixedClock
import tech.beyondscale.mitra.testing.ViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Records note patches, then applies them through the fake. */
private class RecordingNotes(val fake: FakeNotesRepository) : NotesRepository by fake {
    val updates = mutableListOf<Pair<String, List<NoteChange>>>()
    override suspend fun update(id: String, changes: List<NoteChange>) {
        updates += id to changes
        fake.update(id, changes)
    }
}

private fun note(id: String, title: String, body: String = "", folder: String = "Ideas", tags: List<String> = emptyList(), journalDate: String? = null, mood: Int? = null, updatedAt: String = "2026-10-13") =
    Note(id, title, body, folder, tags, journalDate, false, mood, "2026-10-01", updatedAt)

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelsTest : ViewModelTest() {
    // 10:00 on Wed 14 Oct in India
    private val clock = FixedClock(Instant.parse("2026-10-14T04:30:00Z"), TimeZone.of("Asia/Kolkata"))

    private fun <A> TestScope.collect(state: kotlinx.coroutines.flow.Flow<*>, events: kotlinx.coroutines.flow.Flow<UiEvent<A>>): MutableList<UiEvent<A>> {
        val d = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(d) { state.collect {} }
        val out = mutableListOf<UiEvent<A>>()
        backgroundScope.launch(d) { events.toList(out) }
        runCurrent()
        return out
    }

    private fun <T> TestScope.opened(flow: kotlinx.coroutines.flow.Flow<T>): MutableList<T> {
        val out = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.toList(out) }
        return out
    }

    /* ---------- list ---------- */

    @Test fun listFiltersByFolderAndTagAndCreatesThenOpens() = runTest {
        val repo = FakeNotesRepository(
            listOf(note("a", "Plan", "# Q4\n- [ ] ship", tags = listOf("work", "q4")), note("b", "Book", folder = "Learning", tags = listOf("work")), note("c", "Odd", folder = "Recipes")),
        )
        val vm = NotesViewModel(repo, clock)
        val events = collect(vm.state, vm.events)
        val opened = opened(vm.openNote)
        val s = vm.state.value
        assertEquals(3, s.allCount)
        assertEquals(listOf("Work", "Ideas", "Learning", "Journal", "Personal", "Recipes"), s.folders.map { it.name })
        assertEquals(listOf("work" to 2, "q4" to 1), s.tags.map { it.name to it.count })
        assertEquals("Q4\n• ship", s.rows.first().excerpt)
        assertEquals("Yesterday", s.rows.first().updated)

        vm.onAction(NotesAction.Filter(NoteFilter.Folder("Learning")))
        assertEquals(listOf("b"), vm.state.value.rows.map { it.id })
        vm.onAction(NotesAction.Filter(NoteFilter.Tag("work")))
        assertEquals(listOf("a", "b"), vm.state.value.rows.map { it.id })
        vm.onAction(NotesAction.Filter(NoteFilter.Tag("work"))) // tapping the selected chip clears it
        assertEquals(NoteFilter.All, vm.state.value.filter)

        vm.onAction(NotesAction.Filter(NoteFilter.Folder("Learning")))
        vm.onAction(NotesAction.OpenForm)
        assertEquals("Learning", vm.state.value.formFolder)
        vm.onAction(NotesAction.Create("  ", "Learning", ""))
        assertTrue(repo.calls.none { it.startsWith("create") })
        vm.onAction(NotesAction.Create(" Kotlin flows ", "Learning", "#kotlin, async"))
        val created = repo.noteFlow.value.first()
        assertEquals("Kotlin flows", created.title)
        assertEquals(listOf("kotlin", "async"), created.tags)
        assertNull(vm.state.value.formFolder)
        assertEquals(listOf(created.id), opened)
        assertEquals(UiEvent.Snack<NotesAction>("Note created"), events.last())
    }

    @Test fun refreshErrorsSnackExceptSuperseded() = runTest {
        val repo = FakeNotesRepository()
        val vm = NotesViewModel(repo, clock)
        val events = collect(vm.state, vm.events)
        assertTrue(vm.state.value.empty)
        repo.refreshResult = Outcome.Err(AppError.Server(409, NotesRepository.REFRESH_SUPERSEDED, "syncing"))
        vm.onAction(NotesAction.Refresh)
        assertTrue(events.isEmpty())
        repo.refreshResult = Outcome.Err(AppError.Offline)
        vm.onAction(NotesAction.Refresh)
        assertEquals(UiEvent.Snack<NotesAction>("You're offline — showing notes saved on this phone"), events.single())
        assertFalse(vm.state.value.refreshing)
    }

    /* ---------- search ---------- */

    @Test fun searchHighlightsScopesAndCreatesFromQuery() = runTest {
        val notes = FakeNotesRepository(listOf(note("a", "Budget plan", "rent and food"), note("b", "Links", "see [[Budget plan]]")))
        val tasks = FakeTasksRepository(tasks = listOf(Task("t1", "Pay budget bill", due = "2026-10-14")))
        val vm = NotesSearchViewModel(notes, tasks, FakeGoalsRepository(emptyList(), clock), FakeHabitsRepository(), clock)
        val d = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(d) { vm.state.collect {} }
        val opened = opened(vm.openNote)
        vm.onAction(NotesSearchAction.Query("budget"))
        val s = vm.state.value
        assertEquals(3, s.counts[SearchScope.All])
        assertEquals(listOf(SearchKind.Note, SearchKind.Note, SearchKind.Task), s.rows.map { it.kind })
        assertEquals(listOf("Budget" to true, " plan" to false), s.rows[0].title)
        assertEquals("Task · Today", s.rows[2].sub.joinToString("") { it.first })
        assertEquals("Budget plan", s.backlinksFor)
        assertEquals(listOf("b"), s.backlinks.map { it.id })

        vm.onAction(NotesSearchAction.Scope(SearchScope.Tasks))
        assertEquals(listOf("t1"), vm.state.value.rows.map { it.id })

        vm.onAction(NotesSearchAction.Query("Groceries"))
        assertEquals(0, vm.state.value.counts[SearchScope.All])
        vm.onAction(NotesSearchAction.CreateFromQuery)
        assertEquals("Groceries", notes.noteFlow.value.first().title)
        assertEquals(listOf(notes.noteFlow.value.first().id), opened)
    }

    /* ---------- editor ---------- */

    private class Editor(val vm: NoteEditorViewModel, val notes: RecordingNotes, val events: List<UiEvent<NoteEditorAction>>)

    private fun TestScope.editor(vararg initial: Note, tasks: List<Task> = emptyList()): Editor {
        val notes = RecordingNotes(FakeNotesRepository(initial.toList()))
        val vm = NoteEditorViewModel("n1", notes, FakeGoalsRepository(emptyList(), clock), FakeTasksRepository(tasks = tasks), clock)
        return Editor(vm, notes, collect(vm.state, vm.events))
    }

    @Test fun typingIsDebouncedIntoOneUpdate() = runTest {
        val e = editor(note("n1", "Plan", "a"))
        e.vm.onAction(NoteEditorAction.Write(true))
        e.vm.onAction(NoteEditorAction.Body("ab"))
        e.vm.onAction(NoteEditorAction.Title("Plan v2"))
        e.vm.onAction(NoteEditorAction.Body("abc"))
        assertEquals("abc", e.vm.state.value.body)
        assertEquals("Saving…", e.vm.state.value.status)
        assertTrue(e.notes.updates.isEmpty())
        advanceTimeBy(NoteEditorViewModel.AUTOSAVE_MS + 1)
        assertEquals(listOf("n1" to listOf(NoteChange.Title("Plan v2"), NoteChange.Body("abc"))), e.notes.updates)
        assertEquals("Saved", e.vm.state.value.status)
        assertEquals("abc", e.notes.fake.noteFlow.value.single().body)
    }

    @Test fun flushOnStopSavesAndBlankTitleIsKeptAsDraft() = runTest {
        val e = editor(note("n1", "Plan", "a"))
        e.vm.onAction(NoteEditorAction.Title(""))
        e.vm.onAction(NoteEditorAction.Body("typed"))
        e.vm.flushNow()
        runCurrent()
        assertEquals(listOf("n1" to listOf<NoteChange>(NoteChange.Body("typed"))), e.notes.updates)
        assertEquals("", e.vm.state.value.title) // the field keeps the blank title; the note keeps "Plan"
        assertEquals("Plan", e.notes.fake.noteFlow.value.single().title)
        e.vm.flushNow()
        runCurrent()
        assertEquals(1, e.notes.updates.size) // nothing new to send
    }

    @Test fun readViewResolvesLinksAndTogglesChecklist() = runTest {
        val e = editor(
            note("n1", "Plan", "# Q4\n- [ ] Call [[Bank]]\n- [x] done\nsee [[Nowhere]]"),
            note("n2", "Refs", "about [[Plan]]"),
            tasks = listOf(Task("t1", "Bank")),
        )
        val s = e.vm.state.value
        assertEquals(NoteLine.H1("Q4"), s.lines[0].line)
        assertEquals(WikiTarget.ToTask("t1"), s.lines[1].inline.single { it.link }.target)
        assertNull(s.lines[3].inline.single { it.link }.target)
        assertEquals(listOf("Task" to "t1"), s.linked.map { it.kind to it.id })
        assertEquals(listOf("n2"), s.backlinks.map { it.id })
        assertTrue(s.footer.endsWith("12 words"))

        e.vm.onAction(NoteEditorAction.ToggleCheck(1))
        runCurrent()
        assertEquals("# Q4\n- [x] Call [[Bank]]\n- [x] done\nsee [[Nowhere]]", e.notes.updates.single().second.filterIsInstance<NoteChange.Body>().single().v)
        assertEquals(1, e.vm.state.value.bodyRevision)
    }

    @Test fun tagsFolderDuplicateDeleteAndUndo() = runTest {
        val e = editor(note("n1", "Plan", "x", tags = listOf("a")))
        val opened = opened(e.vm.openNote)
        e.vm.onAction(NoteEditorAction.AddTag(" #b "))
        e.vm.onAction(NoteEditorAction.AddTag("a"))
        e.vm.onAction(NoteEditorAction.RemoveTag("a"))
        e.vm.onAction(NoteEditorAction.MoveTo("Work"))
        runCurrent()
        assertEquals(listOf("b"), e.vm.state.value.tags)
        assertEquals("Work", e.vm.state.value.folder)
        assertEquals("Moved to Work", (e.events.last() as UiEvent.Snack).text)

        e.vm.onAction(NoteEditorAction.Duplicate)
        runCurrent()
        val copy = e.notes.fake.noteFlow.value.first { it.id != "n1" }
        assertEquals("Plan (copy)", copy.title)
        assertEquals(listOf(copy.id), opened)

        e.vm.onAction(NoteEditorAction.Body("unsaved"))
        e.vm.onAction(NoteEditorAction.Delete)
        runCurrent()
        assertTrue("delete:n1" in e.notes.fake.calls)
        val snack = e.events.filterIsInstance<UiEvent.Snack<NoteEditorAction>>().last()
        assertEquals("Note deleted", snack.text)
        assertEquals(UiEvent.Close, e.events.last())
        advanceTimeBy(NoteEditorViewModel.AUTOSAVE_MS + 1)
        assertTrue(e.notes.updates.none { u -> u.second.any { it is NoteChange.Body } }) // the draft died with the note

        e.vm.onAction(snack.undo!!)
        runCurrent()
        assertEquals("unsaved", e.notes.fake.noteFlow.value.first { it.id == "n1" }.body)
    }

    /* ---------- journal ---------- */

    private class Journal(val vm: JournalViewModel, val notes: FakeNotesRepository, val wellness: FakeWellnessRepository, val ai: FakeAiRepository, val events: List<UiEvent<JournalAction>>)

    private fun TestScope.journal(notes: List<Note> = emptyList(), entries: Map<String, WellnessEntry> = emptyMap(), aiOn: Boolean = true): Journal {
        val n = FakeNotesRepository(notes)
        val w = FakeWellnessRepository(entries)
        val ai = FakeAiRepository(enabled = aiOn)
        val vm = JournalViewModel(n, w, ai, FakeTasksRepository(tasks = listOf(Task("t", "Ship", due = "2026-10-14"))), FakeHabitsRepository(), FakeMoneyRepository(), FakeActivityRepository(), clock)
        return Journal(vm, n, w, ai, collect(vm.state, vm.events))
    }

    @Test fun journalWeekNavigationAndEarlier() = runTest {
        val j = journal(listOf(note("j1", "Journal — Mon 12 Oct", "calm", "Journal", journalDate = "2026-10-12", mood = 3)))
        val s = j.vm.state.value
        assertEquals("2026-10-14", s.day)
        assertEquals("Today", s.heading)
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), s.week.map { it.dow })
        assertEquals(listOf(true, false, false, false, false, false, false), s.week.map { it.hasEntry })
        assertEquals(listOf(false, false, false, true, true, true, true), s.week.map { it.future })
        assertFalse(s.canNextWeek)
        assertEquals("Mon 12 Oct · 🙂", s.earlier.single().label)

        j.vm.onAction(JournalAction.SelectDay("2026-10-15")) // future: ignored
        assertEquals("2026-10-14", j.vm.state.value.day)
        j.vm.onAction(JournalAction.SelectDay("2026-10-12"))
        assertEquals("j1", j.vm.state.value.entryId)
        assertEquals("calm", j.vm.state.value.text)
        assertEquals(3, j.vm.state.value.mood)
        j.vm.onAction(JournalAction.PrevWeek)
        assertEquals("2026-10-05", j.vm.state.value.day)
        assertTrue(j.vm.state.value.canNextWeek)
        j.vm.onAction(JournalAction.Today)
        assertEquals("2026-10-14", j.vm.state.value.day)
    }

    @Test fun journalAiDraftKeepAndSaveCreatesEntryAndMood() = runTest {
        val j = journal(entries = mapOf("2026-10-14" to WellnessEntry("2026-10-14", mood = 2)))
        assertEquals(2, j.vm.state.value.mood) // the day's check-in mood
        j.vm.onAction(JournalAction.Save)
        assertEquals("Write something first", (j.events.last() as UiEvent.Snack).text)

        j.vm.onAction(JournalAction.Text("Evening walk."))
        j.vm.onAction(JournalAction.Draft)
        assertEquals("A calm day.", j.vm.state.value.draft?.text)
        assertTrue("journal:2026-10-14" in j.ai.calls)
        assertTrue("log:Drafted journal" in j.ai.calls)
        j.vm.onAction(JournalAction.KeepDraft)
        assertEquals("A calm day.\n\nEvening walk.", j.vm.state.value.text)
        j.vm.onAction(JournalAction.Mood(4))
        j.vm.onAction(JournalAction.Save)
        val saved = j.notes.noteFlow.value.single()
        assertEquals("Journal — Wed 14 Oct", saved.title)
        assertEquals("2026-10-14", saved.journalDate)
        assertEquals(listOf("journal"), saved.tags)
        assertEquals("Journal", saved.folder)
        assertTrue(saved.ai)
        assertEquals(4, saved.mood)
        assertTrue("save:2026-10-14" in j.wellness.calls)
        assertEquals(4, j.wellness.entryFlow.value["2026-10-14"]?.mood)
        assertEquals("Journal saved", (j.events.last() as UiEvent.Snack).text)
        assertEquals(saved.id, j.vm.state.value.entryId)

        j.vm.onAction(JournalAction.Text("Rewritten"))
        j.vm.onAction(JournalAction.Save)
        assertEquals("Rewritten", j.notes.noteFlow.value.single().body) // updates, never a second entry
    }

    @Test fun journalDraftFallsBackOfflineWhenAiOffOrFailing() = runTest {
        val j = journal(aiOn = false)
        j.vm.onAction(JournalAction.Draft)
        assertTrue(j.ai.calls.none { it.startsWith("journal:") })
        assertTrue(j.vm.state.value.draft!!.text.contains("**Still open.** Ship."))

        j.vm.onAction(JournalAction.DiscardDraft)
        j.ai.enabledFlow.value = true
        j.ai.journalResult = Outcome.Err(AppError.Server(503, "AI_DOWN", "AI is unavailable"))
        j.vm.onAction(JournalAction.Draft)
        assertEquals("AI is unavailable", (j.events.last() as UiEvent.Snack).text)
        assertTrue(j.vm.state.value.draft!!.text.startsWith("**Morning.**"))
    }
}
