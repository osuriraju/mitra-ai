package tech.beyondscale.mitra.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.MitraLog
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.repository.ModuleRefresher

/**
 * Spec §6 refresh triggers, throttled to once per [minIntervalMs] per module:
 * - signing in to an account other than the last one seen refreshes every module (throttles reset; `"user"` was
 *   just fetched by `restore()`/`login()`);
 * - [onForeground] refreshes stale modules, `"user"` included;
 * - [drained] refreshes stale modules except `"user"`. A module still inside its window is refreshed once the window
 *   ends instead, so every drain that sent rows is followed by a refresh — the safety net for a refresh that applied
 *   nothing because writes kept overtaking it ([tech.beyondscale.mitra.domain.repository.TasksRepository.refresh]).
 * Nothing runs while signed out. Each module runs in its own coroutine; one that throws doesn't affect the others.
 */
class ModuleRefresherImpl(
    session: Flow<SessionState>,
    drained: Flow<Unit>,
    private val clock: AppClock,
    private val scope: CoroutineScope,
    private val modules: Map<String, suspend () -> Unit>,
    private val minIntervalMs: Long = 60_000,
) : ModuleRefresher {
    private val mutex = Mutex()
    private val lastRun = mutableMapOf<String, Long>()
    private val deferred = mutableMapOf<String, Job>()
    private var userId: String? = null

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { session.collect { onSession(it) } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            drained.collect { refreshStale(modules.keys - USER, deferIfFresh = true) }
        }
    }

    override fun onForeground() {
        scope.launch { refreshStale(modules.keys, deferIfFresh = false) }
    }

    private suspend fun onSession(state: SessionState) = mutex.withLock {
        when (state) {
            is SessionState.SignedIn -> {
                if (state.user.id == userId) return@withLock
                userId = state.user.id
                reset()
                val now = nowMs()
                lastRun[USER] = now
                (modules.keys - USER).forEach { start(it, now) }
            }
            // Sign-out (or a 401 / expired session, where data is kept) ends this session: whoever signs in next, even the
            // same account, gets a full refresh.
            SessionState.SignedOut -> { userId = null; reset() }
            SessionState.Unknown -> Unit
        }
    }

    private suspend fun refreshStale(names: Set<String>, deferIfFresh: Boolean) = mutex.withLock {
        if (userId == null) return@withLock
        val now = nowMs()
        for (name in names) {
            val last = lastRun[name]
            when {
                last == null || now - last >= minIntervalMs -> start(name, now)
                deferIfFresh && deferred[name]?.isActive != true -> deferred[name] = scope.launch {
                    // Clamped so a backward wall-clock jump can't postpone the refresh beyond one window.
                    delay((last + minIntervalMs - now).coerceIn(0, minIntervalMs))
                    mutex.withLock {
                        deferred.remove(name)
                        if (userId != null) start(name, nowMs())
                    }
                }
            }
        }
    }

    /** Caller holds [mutex]. A refresh starting now covers any refresh deferred for later. */
    private fun start(name: String, now: Long) {
        val module = modules[name] ?: return
        deferred.remove(name)?.cancel()
        lastRun[name] = now
        scope.launch {
            try { module() } catch (e: CancellationException) { throw e } catch (e: Exception) { MitraLog.warn("MitraSync", "refresh of $name failed", e) }
        }
    }

    /** Caller holds [mutex]. */
    private fun reset() {
        lastRun.clear()
        deferred.values.forEach { it.cancel() }
        deferred.clear()
    }

    private fun nowMs() = clock.now().toEpochMilliseconds()

    private companion object { const val USER = "user" }
}
