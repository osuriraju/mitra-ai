package tech.beyondscale.mitra.feature.money

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.diffDays
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.core.fmtShort
import tech.beyondscale.mitra.core.inr
import tech.beyondscale.mitra.core.inrPaise
import tech.beyondscale.mitra.domain.model.Cycle
import tech.beyondscale.mitra.domain.model.Subscription
import tech.beyondscale.mitra.domain.money.renewalsWithin30
import tech.beyondscale.mitra.domain.money.subscriptionsMonthlyPaise
import tech.beyondscale.mitra.domain.money.unusedSubscriptions
import tech.beyondscale.mitra.domain.repository.MoneyRepository
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.UiEvent

data class SubscriptionRowUi(
    val id: String,
    val name: String,
    val letter: String,
    /** Hex colour of the letter logo, e.g. "#E50914". */
    val color: String,
    val sub: String,
    val amount: String,
    /** "Not used 45d" when unused for more than 30 days. */
    val unusedChip: String?,
)

data class SubscriptionsUiState(
    val loaded: Boolean = false,
    val monthly: String = "",
    val monthlySub: String = "",
    val yearly: String = "",
    val unusedCount: String = "",
    val unusedSub: String = "",
    val rows: List<SubscriptionRowUi> = emptyList(),
    /** Renewals in the next 30 days, soonest first (paise) with `fmtShort` labels. */
    val renewalPaise: List<Long> = emptyList(),
    val renewalLabels: List<String> = emptyList(),
)

sealed interface SubscriptionsAction {
    data class Add(val name: String, val amountPaise: Long, val cycle: Cycle) : SubscriptionsAction
    data class MarkUsed(val id: String) : SubscriptionsAction
    data object CancelGuidance : SubscriptionsAction
    data class Delete(val id: String) : SubscriptionsAction
}

class SubscriptionsViewModel(private val money: MoneyRepository, private val clock: AppClock) : ViewModel() {
    private val queue = EventQueue<SubscriptionsAction>()
    val events: Flow<UiEvent<SubscriptionsAction>> = queue.events

    val state: StateFlow<SubscriptionsUiState> = combine(money.subscriptions, clock.dayFlow(), ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubscriptionsUiState())

    fun onAction(a: SubscriptionsAction) {
        when (a) {
            is SubscriptionsAction.Add -> {
                val name = a.name.trim()
                if (name.isEmpty() || a.amountPaise <= 0) return
                viewModelScope.launch {
                    money.addSubscription(name, a.amountPaise, a.cycle)
                    queue.send(UiEvent.Snack("Subscription added · $name"))
                }
            }
            is SubscriptionsAction.MarkUsed -> viewModelScope.launch {
                val s = money.subscriptions.first().firstOrNull { it.id == a.id } ?: return@launch
                money.markSubscriptionUsed(a.id)
                queue.send(UiEvent.Snack("${s.name} · used today"))
            }
            SubscriptionsAction.CancelGuidance ->
                queue.send(UiEvent.Snack("Mitra never cancels anything itself — open the service to cancel"))
            is SubscriptionsAction.Delete -> viewModelScope.launch {
                money.deleteSubscription(a.id)
                queue.send(UiEvent.Snack("Removed"))
            }
        }
    }

    private fun build(subs: List<Subscription>, today: String): SubscriptionsUiState {
        val monthly = subscriptionsMonthlyPaise(subs)
        val unused = unusedSubscriptions(subs, today)
        val renewals = renewalsWithin30(subs, today)
        return SubscriptionsUiState(
            loaded = true,
            monthly = inr(monthly / 100.0),
            monthlySub = "${subs.size} active",
            yearly = inr(monthly * 12 / 100.0),
            unusedCount = unused.size.toString(),
            unusedSub = unused.joinToString(", ") { it.name.split(' ')[0] }.ifEmpty { "none" },
            rows = subs.map { s ->
                val last = s.lastUsed
                SubscriptionRowUi(
                    id = s.id, name = s.name, letter = s.letter, color = s.color,
                    sub = "${s.cycle.wire} · next ${fmtDay(s.next, today)}" + (if (last != null) " · last used ${fmtDay(last, today)}" else ""),
                    amount = inrPaise(s.amountPaise),
                    unusedChip = if (last != null && unused.any { it.id == s.id }) "Not used ${diffDays(today, last)}d" else null,
                )
            },
            renewalPaise = renewals.map { it.amountPaise },
            renewalLabels = renewals.map { fmtShort(it.next) },
        )
    }
}
