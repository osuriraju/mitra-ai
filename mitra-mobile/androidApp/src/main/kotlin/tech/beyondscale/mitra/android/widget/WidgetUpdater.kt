package tech.beyondscale.mitra.android.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.domain.repository.AuthRepository
import tech.beyondscale.mitra.domain.repository.MoneyRepository
import tech.beyondscale.mitra.domain.repository.PreferencesRepository
import tech.beyondscale.mitra.domain.repository.TasksRepository
import tech.beyondscale.mitra.domain.tasks.tasksWidgetModel

/**
 * Keeps the widgets current while the process is alive: local edits, sync echoes, sign-out, theme changes and local
 * midnight all redraw it (debounced), but only when what the widget shows actually changed. The first value is what
 * the widget already shows, so app start does not redraw. [MidnightReceiver] covers midnight when the process is
 * gone. No polling.
 */
class WidgetUpdater(
    private val context: Context,
    private val tasks: TasksRepository,
    private val money: MoneyRepository,
    private val auth: AuthRepository,
    private val prefs: PreferencesRepository,
    private val clock: AppClock,
    private val scope: CoroutineScope,
) {
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch { MidnightReceiver.scheduleIfPlaced(context) }
        scope.launch {
            combine(tasks.tasks, auth.hasLocalAccount, prefs.theme, clock.dayFlow()) { list, signedIn, theme, today ->
                tasksWidgetModel(list, today, signedIn) to theme
            }
                .distinctUntilChanged()
                .drop(1)
                .debounce(DEBOUNCE_MS)
                .collect { TasksWidget().updateAll(context) }
        }
        // Money widget: compared with amounts shown (each widget masks its own on redraw), so a change behind
        // "Hide amounts" still redraws — cheap, and the hidden widget simply renders the same mask.
        scope.launch {
            combine(moneyWidgetInputs(money, auth, clock), prefs.theme) { inputs, theme -> inputs.model(hideAmounts = false) to theme }
                .distinctUntilChanged()
                .drop(1)
                .debounce(DEBOUNCE_MS)
                .collect { MoneyWidget().updateAll(context) }
        }
    }

    private companion object { const val DEBOUNCE_MS = 500L }
}
