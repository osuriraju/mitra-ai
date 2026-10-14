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
import tech.beyondscale.mitra.core.AppError
import tech.beyondscale.mitra.core.Outcome
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.domain.model.NOTE_FOLDERS
import tech.beyondscale.mitra.domain.model.Note
import tech.beyondscale.mitra.domain.model.NoteDraft
import tech.beyondscale.mitra.domain.notes.excerpt
import tech.beyondscale.mitra.domain.notes.folderCounts
import tech.beyondscale.mitra.domain.notes.parseTags
import tech.beyondscale.mitra.domain.notes.tagCounts
import tech.beyondscale.mitra.domain.repository.NotesRepository
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.UiEvent

/** What the list shows: everything, one folder, or one tag (web Notes chips + Folders & tags screen). */
sealed interface NoteFilter {
    data object All : NoteFilter
    data class Folder(val name: String) : NoteFilter
    data class Tag(val name: String) : NoteFilter
}

data class NoteRowUi(
    val id: String,
    val title: String,
    val folder: String,
    val excerpt: String,
    /** First three, "#tag". */
    val tags: List<String>,
    /** "Today" · "Mon 12 Oct". */
    val updated: String,
    val ai: Boolean,
    val journal: Boolean,
)

data class CountChipUi(val name: String, val count: Int, val selected: Boolean)

data class NotesUiState(
    val loaded: Boolean = false,
    val filter: NoteFilter = NoteFilter.All,
    val allCount: Int = 0,
    val folders: List<CountChipUi> = emptyList(),
    val tags: List<CountChipUi> = emptyList(),
    val rows: List<NoteRowUi> = emptyList(),
    /** No notes at all (first-run empty state). */
    val empty: Boolean = false,
    val refreshing: Boolean = false,
    /** The new-note sheet's default folder; null = closed. */
    val formFolder: String? = null,
)

sealed interface NotesAction {
    data class Filter(val filter: NoteFilter) : NotesAction
    data object Refresh : NotesAction
    data object OpenForm : NotesAction
    data object CloseForm : NotesAction
    /** [tags] as typed: "ideas, #money". A blank title is ignored (web NoteForm). */
    data class Create(val title: String, val folder: String, val tags: String) : NotesAction
}

/** Web `NotesScreen` + `NotesFoldersScreen` + `NoteForm`. A created note is opened through [openNote]. */
class NotesViewModel(private val notes: NotesRepository, clock: AppClock) : ViewModel() {
    private val queue = EventQueue<NotesAction>()
    val events: Flow<UiEvent<NotesAction>> = queue.events
    private val open = Channel<String>(Channel.BUFFERED)
    /** Note ids to navigate to (after create). */
    val openNote: Flow<String> = open.receiveAsFlow()

    private data class Local(val filter: NoteFilter = NoteFilter.All, val refreshing: Boolean = false, val formFolder: String? = null)
    private val local = MutableStateFlow(Local())

    val state: StateFlow<NotesUiState> = combine(notes.notes, clock.dayFlow(), local, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesUiState())

    fun onAction(a: NotesAction) {
        when (a) {
            is NotesAction.Filter -> local.update { it.copy(filter = if (it.filter == a.filter) NoteFilter.All else a.filter) }
            NotesAction.Refresh -> refresh()
            NotesAction.OpenForm -> local.update { l -> l.copy(formFolder = (l.filter as? NoteFilter.Folder)?.name ?: "Personal") }
            NotesAction.CloseForm -> local.update { it.copy(formFolder = null) }
            is NotesAction.Create -> {
                if (a.title.isBlank()) return
                local.update { it.copy(formFolder = null) }
                viewModelScope.launch {
                    val n = notes.create(NoteDraft(title = a.title.trim(), folder = a.folder, tags = parseTags(a.tags)))
                    queue.send(UiEvent.Snack("Note created"))
                    open.trySend(n.id)
                }
            }
        }
    }

    private fun refresh() {
        if (local.value.refreshing) return
        local.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                val r = notes.refresh()
                val superseded = r is Outcome.Err && (r.error as? AppError.Server)?.code == NotesRepository.REFRESH_SUPERSEDED
                if (r is Outcome.Err && !superseded) {
                    queue.send(UiEvent.Snack(if (r.error is AppError.Offline) "You're offline — showing notes saved on this phone" else r.error.message))
                }
            } finally {
                local.update { it.copy(refreshing = false) }
            }
        }
    }

    private fun build(all: List<Note>, today: String, l: Local): NotesUiState {
        val shown = when (val f = l.filter) {
            NoteFilter.All -> all
            is NoteFilter.Folder -> all.filter { it.folder == f.name }
            is NoteFilter.Tag -> all.filter { f.name in it.tags }
        }
        return NotesUiState(
            loaded = true,
            filter = l.filter,
            allCount = all.size,
            folders = folderCounts(all).map { (f, n) -> CountChipUi(f, n, l.filter == NoteFilter.Folder(f)) } +
                // Folders made on the web outside the five defaults still get a chip.
                all.map { it.folder }.distinct().filter { it !in NOTE_FOLDERS }.map { f -> CountChipUi(f, all.count { it.folder == f }, l.filter == NoteFilter.Folder(f)) },
            tags = tagCounts(all).map { (t, n) -> CountChipUi(t, n, l.filter == NoteFilter.Tag(t)) },
            rows = shown.map { it.toRowUi(today) },
            empty = all.isEmpty(),
            refreshing = l.refreshing,
            formFolder = l.formFolder,
        )
    }
}

internal fun Note.toRowUi(today: String) = NoteRowUi(
    id = id, title = title, folder = folder, excerpt = excerpt(body), tags = tags.take(3).map { "#$it" },
    updated = if (updatedAt.isEmpty()) "" else fmtDay(updatedAt, today), ai = ai, journal = journalDate != null,
)
