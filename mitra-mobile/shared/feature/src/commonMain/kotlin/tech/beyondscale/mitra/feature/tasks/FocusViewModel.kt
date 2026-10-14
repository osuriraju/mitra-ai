package tech.beyondscale.mitra.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.minsToHm
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.Settings
import tech.beyondscale.mitra.domain.model.Task
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.domain.tasks.focusQueue
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.domain.tasks.complete
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent
import kotlin.math.roundToInt

data class FocusUiState(
    /** False until the first database read, so the empty screen never flashes. */
    val loaded: Boolean = false,
    val empty: Boolean = false,
    val title: String? = null,
    /** "1 of 3 · Today · 1 session" */
    val position: String = "",
    /** "25:00" */
    val clock: String = "",
    val running: Boolean = false,
    /** The countdown reached zero (the clock shows in the success colour). */
    val finished: Boolean = false,
    /** Quarters of the session reached, 0..4. */
    val segments: Int = 0,
    /** "Next: Email Ravi · 30m · " — the screen appends "Time is logged when you finish." */
    val next: String? = null,
)

sealed interface FocusAction {
    data object PauseResume : FocusAction
    data object Done : FocusAction
    data object Skip : FocusAction
    data object Reset : FocusAction
    data object AddFive : FocusAction
    data object OpenTask : FocusAction
}

/** Focus mode (web `FocusScreen`): a countdown over today's open tasks, [taskId] first when given (`""` = none). */
class FocusViewModel(
    taskId: String,
    private val tasks: TasksRepository,
    auth: AuthRepository,
    clock: AppClock,
) : ViewModel() {
    private data class Inputs(val queue: List<Task>, val focusMinutes: Int)

    private data class Timer(
        val idx: Int = 0,
        /** The task the countdown belongs to; a different current task restarts it. */
        val curId: String? = null,
        val total: Int = 0,
        val left: Int = 0,
        val running: Boolean = false,
        val sessions: Int = 0,
    )

    private val pinned = taskId.ifBlank { null }
    private val timer = MutableStateFlow(Timer())
    private var ticker: Job? = null
    private var completing = false
    /** The screen is backgrounded: no ticking, so a stale "Session complete" can't fire. */
    private var stopped = false

    private val queue = EventQueue<FocusAction>()
    val events: Flow<UiEvent<FocusAction>> = queue.events

    private val openTaskChannel = Channel<String>(Channel.BUFFERED)
    /** The current task's id when the user picks "Open task". */
    val openTask: Flow<String> = openTaskChannel.receiveAsFlow()

    /** The latest queue; [sync] restarts the countdown when the current task changes. */
    private var latest: Inputs? = null

    private val inputs: Flow<Inputs> = combine(tasks.tasks, clock.dayFlow(), auth.session) { all, today, session ->
        val minutes = (session as? SessionState.SignedIn)?.user?.settings?.focusMinutes?.takeIf { it > 0 } ?: Settings().focusMinutes
        Inputs(focusQueue(all, today, pinned), minutes)
    }.onEach {
        latest = it
        sync()
    }

    val state: StateFlow<FocusUiState> = combine(inputs, timer, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FocusUiState())

    fun onAction(a: FocusAction) {
        when (a) {
            FocusAction.PauseResume -> if (timer.value.running) {
                ticker?.cancel()
                timer.update { it.copy(running = false) }
            } else if (current() != null) {
                timer.update { it.copy(running = true) }
                startTicker()
            }
            FocusAction.Reset -> if (current() != null) {
                timer.update { it.copy(left = it.total, running = true) }
                startTicker()
            }
            FocusAction.AddFive -> timer.update { it.copy(left = it.left + 300) }
            FocusAction.Skip -> skip()
            FocusAction.Done -> done()
            FocusAction.OpenTask -> current()?.let { openTaskChannel.trySend(it.id) }
        }
    }

    /** Screen stopped: pause the countdown, keeping the remaining time and the running flag. */
    fun onStop() {
        stopped = true
        ticker?.cancel()
    }

    /** Screen started again: resume the countdown if it was running. */
    fun onStart() {
        stopped = false
        if (timer.value.running) startTicker()
    }

    private fun current(): Task? = latest?.queue?.getOrNull(timer.value.idx)

    /** Restarts the countdown whenever the current task changes (web: effect on `cur?.id`). */
    private fun sync() {
        val inp = latest ?: return
        val cur = inp.queue.getOrNull(timer.value.idx)
        if (cur?.id == timer.value.curId) return
        val total = (cur?.estMin?.takeIf { it > 0 } ?: inp.focusMinutes) * 60
        timer.update { it.copy(curId = cur?.id, total = total, left = total, running = cur != null) }
        if (cur != null) startTicker() else ticker?.cancel()
    }

    private fun startTicker() {
        ticker?.cancel()
        if (stopped) return
        ticker = viewModelScope.launch {
            while (timer.value.running) {
                delay(1_000)
                tick()
            }
        }
    }

    private fun tick() {
        val t = timer.value
        if (!t.running) return
        if (t.left > 1) {
            timer.value = t.copy(left = t.left - 1)
            return
        }
        timer.value = t.copy(left = 0, running = false, sessions = t.sessions + 1)
        queue.send(UiEvent.Snack("Session complete — take a short break"))
        queue.send(UiEvent.Haptic(HapticKind.Confirm))
    }

    private fun skip() {
        val inp = latest ?: return
        val idx = timer.value.idx
        if (idx + 1 < inp.queue.size) {
            timer.update { it.copy(idx = idx + 1) }
            sync()
        } else {
            queue.send(UiEvent.Snack("That was the last task for today"))
            queue.send(UiEvent.Close)
        }
    }

    private fun done() {
        val inp = latest ?: return
        val idx = timer.value.idx
        val cur = inp.queue.getOrNull(idx) ?: return
        if (completing) return
        completing = true
        val t = timer.value
        val spent = ((t.total - t.left) / 60.0).roundToInt()
        val last = idx + 1 >= inp.queue.size
        viewModelScope.launch {
            try {
                tasks.complete(cur.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                queue.send(UiEvent.Snack("Couldn't complete the task"))
                return@launch
            } finally {
                completing = false
            }
            queue.send(UiEvent.Snack("Completed · ${cur.title} · $spent min logged"))
            queue.send(UiEvent.Haptic(HapticKind.Confirm))
            if (last) queue.send(UiEvent.Close)
        }
    }

    private fun build(inp: Inputs, t: Timer): FocusUiState {
        val q = inp.queue
        val cur = q.getOrNull(t.idx) ?: return FocusUiState(loaded = true, empty = true)
        val sessions = if (t.sessions > 0) " · ${t.sessions} session${if (t.sessions > 1) "s" else ""}" else ""
        val pct = if (t.total > 0) (t.total - t.left) * 100.0 / t.total else 0.0
        val next = q.getOrNull(t.idx + 1)
        return FocusUiState(
            loaded = true,
            title = cur.title,
            position = "${t.idx + 1} of ${q.size} · Today$sessions",
            clock = "${(t.left / 60).toString().padStart(2, '0')}:${(t.left % 60).toString().padStart(2, '0')}",
            running = t.running,
            finished = t.left == 0,
            segments = (0..3).count { i -> pct >= 25 * (i + 1) },
            next = next?.let { n -> "Next: ${n.title}${n.estMin?.takeIf { it > 0 }?.let { " · " + minsToHm(it) } ?: ""} · " },
        )
    }
}
