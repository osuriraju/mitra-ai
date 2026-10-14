package tech.beyondscale.mitra.android.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.PreferencesRepository
import tech.beyondscale.mitra.domain.repository.TasksRepository

/**
 * Keeps the widget current while the process is alive: local edits, sync echoes, sign-out, theme changes and local
 * midnight all redraw it (debounced). [MidnightReceiver] covers midnight when the process is gone. No polling.
 */
class WidgetUpdater(
    private val context: Context,
    private val tasks: TasksRepository,
    private val auth: AuthRepository,
    private val prefs: PreferencesRepository,
    private val clock: AppClock,
    private val scope: CoroutineScope,
) {
    @OptIn(FlowPreview::class)
    fun start() {
        MidnightReceiver.schedule(context)
        scope.launch {
            combine(tasks.tasks, auth.hasLocalAccount, prefs.theme, clock.dayFlow()) { _, _, _, _ -> }
                .debounce(DEBOUNCE_MS)
                .collect { TasksWidget().updateAll(context) }
        }
    }

    private companion object { const val DEBOUNCE_MS = 500L }
}
