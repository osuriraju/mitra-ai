package tech.beyondscale.mitra.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.domain.model.Goal
import tech.beyondscale.mitra.domain.model.NOTE_FOLDERS
import tech.beyondscale.mitra.domain.model.Note
import tech.beyondscale.mitra.domain.model.NoteChange
import tech.beyondscale.mitra.domain.model.NoteDraft
import tech.beyondscale.mitra.domain.model.Project
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.notes.NoteLine
import tech.beyondscale.mitra.domain.notes.WikiTarget
import tech.beyondscale.mitra.domain.notes.addTag
import tech.beyondscale.mitra.domain.notes.backlinks
import tech.beyondscale.mitra.domain.notes.noteLinks
import tech.beyondscale.mitra.domain.notes.parseNoteBody
import tech.beyondscale.mitra.domain.notes.resolveWikiLink
import tech.beyondscale.mitra.domain.notes.toggleChecklistLine
import tech.beyondscale.mitra.domain.notes.wikiSegments
import tech.beyondscale.mitra.domain.notes.wordCount
import tech.beyondscale.mitra.domain.repository.GoalsRepository
import tech.beyondscale.mitra.domain.repository.NotesRepository
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.UiEvent

/** A run of a rendered line: plain text, or a `[[link]]` with what it resolves to (null = no match). */
data class InlineUi(val text: String, val link: Boolean = false, val target: WikiTarget? = null)

/** One rendered line of the body in read mode; [index] is the body line (checklist toggles use it). */
data class BodyLineUi(val index: Int, val line: NoteLine, val inline: List<InlineUi>)

data class LinkedNoteUi(val kind: String, val id: String, val title: String)

data class NoteEditorUiState(
    val loaded: Boolean = false,
    val notFound: Boolean = false,
    val writing: Boolean = false,
    val title: String = "",
    val body: String = "",
    /** Bumped when the body changes other than by typing (load, checklist tap): the field re-reads [body]. */
    val bodyRevision: Int = 0,
    val folder: String = "Personal",
    val folders: List<String> = NOTE_FOLDERS,
    val tags: List<String> = emptyList(),
    val ai: Boolean = false,
    val journalDate: String? = null,
    /** "Saving…" · "Saved" · "Edited Today". */
    val status: String = "",
    val lines: List<BodyLineUi> = emptyList(),
    val linked: List<LinkedNoteUi> = emptyList(),
    val backlinks: List<LinkedNoteUi> = emptyList(),
    /** "Created Mon 12 Oct · edited Today · 120 words" */
    val footer: String = "",
)

sealed interface NoteEditorAction {
    data class Write(val on: Boolean) : NoteEditorAction
    data class Title(val text: String) : NoteEditorAction
    data class Body(val text: String) : NoteEditorAction
    data class ToggleCheck(val index: Int) : NoteEditorAction
    data class AddTag(val tag: String) : NoteEditorAction
    data class RemoveTag(val tag: String) : NoteEditorAction
    data class MoveTo(val folder: String) : NoteEditorAction
    data object Duplicate : NoteEditorAction
    data object Delete : NoteEditorAction
    /** Undo of [Delete]. */
    data class Restore(val note: Note) : NoteEditorAction
}

/**
 * Web `NoteEditorScreen`, full screen. Title and body are local drafts while typing; 600 ms after the last keystroke
 * (or on stop / dispose — the route calls [flushNow]) the changed fields go to [NotesRepository.update], whose
 * PATCH coalesces per note in the outbox. Writes run on a scope that outlives the ViewModel so a flush from
 * [onCleared] lands; a mutex keeps them in order.
 */
class NoteEditorViewModel(
    private val id: String,
    private val notes: NotesRepository,
    goals: GoalsRepository,
    tasks: TasksRepository,
    clock: AppClock,
    startWriting: Boolean = false,
) : ViewModel() {
    private val queue = EventQueue<NoteEditorAction>()
    val events: Flow<UiEvent<NoteEditorAction>> = queue.events
    private val open = Channel<String>(Channel.BUFFERED)
    /** Note ids to navigate to (Duplicate). */
    val openNote: Flow<String> = open.receiveAsFlow()

    private data class Local(
        val writing: Boolean,
        val title: String? = null,
        val body: String? = null,
        val revision: Int = 0,
        val saved: Boolean = false,
        val deleted: Boolean = false,
    )
    private val local = MutableStateFlow(Local(writing = startWriting))

    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writeLock = Mutex()
    private var debounce: Job? = null

    /** The note as last read from the store (for undo snapshots and checklist taps). */
    private var lastNote: Note? = null

    private data class Inputs(val notes: List<Note>, val goals: List<Goal>, val tasks: List<Task>, val projects: List<Project>)

    val state: StateFlow<NoteEditorUiState> =
        combine(combine(notes.notes, goals.goals, tasks.tasks, tasks.projects, ::Inputs), clock.dayFlow(), local, ::build)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteEditorUiState())

    fun onAction(a: NoteEditorAction) {
        when (a) {
            is NoteEditorAction.Write -> {
                if (!a.on) flushNow()
                local.update { it.copy(writing = a.on) }
            }
            is NoteEditorAction.Title -> edit { it.copy(title = a.text) }
            is NoteEditorAction.Body -> edit { it.copy(body = a.text) }
            is NoteEditorAction.ToggleCheck -> {
                val body = local.value.body ?: lastNote?.body ?: return
                edit { it.copy(body = toggleChecklistLine(body, a.index), revision = it.revision + 1) }
                flushNow()
            }
            is NoteEditorAction.AddTag -> {
                val tag = a.tag.trim().removePrefix("#").trim()
                if (tag.isEmpty()) return
                change { listOf(NoteChange.Tags(addTag(it.tags, tag))) }
            }
            is NoteEditorAction.RemoveTag -> change { listOf(NoteChange.Tags(it.tags - a.tag)) }
            is NoteEditorAction.MoveTo -> change {
                queue.send(UiEvent.Snack("Moved to ${a.folder}"))
                listOf(NoteChange.Folder(a.folder))
            }
            NoteEditorAction.Duplicate -> {
                flushNow()
                writes.launch {
                    val n = current() ?: return@launch
                    val copy = writeLock.withLock {
                        notes.create(NoteDraft("${n.title} (copy)", n.body, n.folder, n.tags, null, n.ai, n.mood))
                    }
                    open.trySend(copy.id)
                }
            }
            NoteEditorAction.Delete -> {
                val n = currentWithDrafts() ?: return
                debounce?.cancel()
                local.update { it.copy(title = null, body = null, deleted = true) }
                writes.launch {
                    writeLock.withLock { notes.delete(id) }
                    queue.send(UiEvent.Snack("Note deleted", NoteEditorAction.Restore(n)))
                    queue.send(UiEvent.Close)
                }
            }
            // Runs from the shell's snackbar after this screen has gone: on [writes], which is never cancelled.
            is NoteEditorAction.Restore -> writes.launch { writeLock.withLock { notes.restore(a.note) } }
        }
    }

    /** Saves the pending title / body draft now (the screen is stopping or going away). */
    fun flushNow() {
        debounce?.cancel()
        debounce = null
        flush()
    }

    // viewModelScope is already cancelled here; the writes run on [writes].
    override fun onCleared() = flushNow()

    private fun edit(f: (Local) -> Local) {
        local.update { f(it).copy(saved = false) }
        debounce?.cancel()
        debounce = viewModelScope.launch {
            delay(AUTOSAVE_MS)
            debounce = null
            flush()
        }
    }

    private fun flush() {
        val l = local.value
        if (l.deleted || (l.title == null && l.body == null)) return
        val title = l.title
        val body = l.body
        writes.launch {
            val n = current() ?: return@launch
            val changes = buildList {
                // A blank title isn't saved (the server needs one); the field keeps showing what was typed.
                if (title != null && title.isNotBlank() && title != n.title) add(NoteChange.Title(title))
                if (body != null && body != n.body) add(NoteChange.Body(body))
            }
            try {
                if (changes.isNotEmpty()) writeLock.withLock { notes.update(id, changes) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch // left as a draft: the next keystroke, stop or clear retries
            }
            // The store now holds the text (local-first): show it unless the user typed more meanwhile.
            local.update { cur ->
                cur.copy(
                    title = if (cur.title == title && !title.isNullOrBlank()) null else cur.title,
                    body = if (cur.body == body) null else cur.body,
                    saved = true,
                )
            }
        }
    }

    /** A non-text change (tags, folder): pending text goes first so the PATCHes stay in typing order. */
    private fun change(changes: suspend (Note) -> List<NoteChange>) {
        flushNow()
        writes.launch {
            writeLock.withLock {
                val n = current() ?: return@withLock
                notes.update(id, changes(n))
            }
        }
    }

    private suspend fun current(): Note? = notes.notes.first().firstOrNull { it.id == id }

    private fun currentWithDrafts(): Note? {
        val s = state.value
        if (!s.loaded || s.notFound) return null
        val l = local.value
        return lastNote?.let { n -> n.copy(title = l.title?.takeIf { it.isNotBlank() } ?: n.title, body = l.body ?: n.body) }
    }

    private fun build(i: Inputs, today: String, l: Local): NoteEditorUiState {
        val n = i.notes.firstOrNull { it.id == id } ?: return NoteEditorUiState(loaded = true, notFound = true)
        lastNote = n
        val body = l.body ?: n.body
        val links = noteLinks(n.copy(body = body), i.goals, i.tasks, i.projects)
        return NoteEditorUiState(
            loaded = true,
            writing = l.writing,
            title = l.title ?: n.title,
            body = body,
            bodyRevision = l.revision,
            folder = n.folder,
            folders = (NOTE_FOLDERS + n.folder).distinct(),
            tags = n.tags,
            ai = n.ai,
            journalDate = n.journalDate,
            status = when {
                l.title != null || l.body != null -> "Saving…"
                l.saved -> "Saved"
                n.updatedAt.isNotEmpty() -> "Edited ${fmtDay(n.updatedAt, today)}"
                else -> ""
            },
            lines = parseNoteBody(body).mapIndexed { idx, line -> BodyLineUi(idx, line, inline(line, i)) },
            linked = links.goals.map { LinkedNoteUi("Goal", it.id, it.name) } +
                links.projects.map { LinkedNoteUi("Project", it.id, it.name) } +
                links.tasks.map { LinkedNoteUi("Task", it.id, it.title) },
            backlinks = backlinks(i.notes, n).map { LinkedNoteUi("Note", it.id, it.title) },
            footer = buildString {
                if (n.createdAt.isNotEmpty()) append("Created ${fmtDay(n.createdAt, today)} · ")
                if (n.updatedAt.isNotEmpty()) append("edited ${fmtDay(n.updatedAt, today)} · ")
                append("${wordCount(body)} words")
            },
        )
    }

    private fun inline(line: NoteLine, i: Inputs): List<InlineUi> {
        val text = when (line) {
            is NoteLine.H1 -> line.text
            is NoteLine.H2 -> line.text
            is NoteLine.Check -> line.text
            is NoteLine.Bullet -> line.text
            is NoteLine.Para -> line.text
            NoteLine.Blank -> return emptyList()
        }
        return wikiSegments(text).map { s ->
            if (s.isLink) InlineUi(s.text, link = true, target = resolveWikiLink(s.text, i.notes, i.goals, i.tasks)) else InlineUi(s.text)
        }
    }

    companion object {
        /** Web autosave debounce. */
        const val AUTOSAVE_MS = 600L
    }
}
