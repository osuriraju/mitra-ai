package tech.beyondscale.mitra.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.fmtClock
import tech.beyondscale.mitra.core.fmtLong
import tech.beyondscale.mitra.core.greeting
import tech.beyondscale.mitra.core.localNow
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.repository.AuthRepository

data class StatUi(val label: String, val value: String, val sub: String)
data class TodayUiState(val dateLine: String, val greeting: String, val stats: List<StatUi>)

/** Phase 1: header + stat strip skeleton. Each later module fills its stat and Today section. */
class TodayViewModel(auth: AuthRepository, private val clock: AppClock) : ViewModel() {
    val state: StateFlow<TodayUiState> = auth.session.map(::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), build(auth.session.value))

    private fun build(session: SessionState): TodayUiState {
        val now = clock.localNow()
        val first = (session as? SessionState.SignedIn)?.user?.firstName
        return TodayUiState(
            dateLine = "${fmtLong(now.date.toString())} · ${fmtClock(now.hour, now.minute)}",
            greeting = greeting(now.hour) + (first?.let { ", $it" } ?: ""),
            stats = listOf(
                StatUi("Tasks", "—", "Done today"),
                StatUi("Habits", "—", "Due today"),
                StatUi("Spent today", "—", "Of monthly budget"),
                StatUi("Sleep", "—", "Last night"),
            ),
        )
    }
}
