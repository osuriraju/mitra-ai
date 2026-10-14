package tech.beyondscale.mitra.feature.money

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.AppClock
import tech.beyondscale.mitra.core.dayFlow
import tech.beyondscale.mitra.core.fmtDay
import tech.beyondscale.mitra.core.inrPaise
import tech.beyondscale.mitra.core.ordinal
import tech.beyondscale.mitra.core.today
import tech.beyondscale.mitra.domain.model.Recurring
import tech.beyondscale.mitra.domain.model.RecurringDraft
import tech.beyondscale.mitra.domain.model.RecurringKind
import tech.beyondscale.mitra.domain.model.Subscription
import tech.beyondscale.mitra.domain.money.daysToDay
import tech.beyondscale.mitra.domain.money.dueSoon
import tech.beyondscale.mitra.domain.money.paidThisMonth
import tech.beyondscale.mitra.domain.money.recurringMonthTotal
import tech.beyondscale.mitra.domain.repository.MoneyRepository
import tech.beyondscale.mitra.domain.repository.PayResult
import tech.beyondscale.mitra.feature.common.EventQueue
import tech.beyondscale.mitra.feature.common.HapticKind
import tech.beyondscale.mitra.feature.common.UiEvent

enum class RecurringTab(val label: String, val kind: RecurringKind) {
    Bills("Bills", RecurringKind.Bill),
    Income("Income", RecurringKind.Income),
    Transfers("Transfers", RecurringKind.Transfer),
}

data class RecurringRowUi(
    val id: String,
    val emoji: String,
    val name: String,
    val sub: String,
    val amount: String,
    val right: String,
    val reminder: Boolean,
    /** "Paid Today" once this month's payment is recorded; the Due soon row then shows a chip instead of the button. */
    val paidLabel: String?,
    /** A Mark paid call is running for this row. */
    val busy: Boolean,
)

data class RecurringUiState(
    val loaded: Boolean = false,
    val tab: RecurringTab = RecurringTab.Bills,
    val monthTotal: String = "",
    val monthSub: String = "",
    val next7: String = "",
    val next7Sub: String = "",
    /** Bills due within 7 days (shown on the Bills tab only). */
    val dueSoon: List<RecurringRowUi> = emptyList(),
    /** Items of the selected tab, by day of month. */
    val all: List<RecurringRowUi> = emptyList(),
)

sealed interface RecurringAction {
    data class SelectTab(val tab: RecurringTab) : RecurringAction
    data class Add(val name: String, val emoji: String, val amountPaise: Long, val day: Int) : RecurringAction
    data class MarkPaid(val id: String) : RecurringAction
    data class SetReminder(val id: String, val on: Boolean) : RecurringAction
    data class Delete(val id: String) : RecurringAction
}

class RecurringViewModel(private val money: MoneyRepository, private val clock: AppClock) : ViewModel() {
    private val queue = EventQueue<RecurringAction>()
    val events: Flow<UiEvent<RecurringAction>> = queue.events

    private val tab = MutableStateFlow(RecurringTab.Bills)
    private val paying = MutableStateFlow<Set<String>>(emptySet())
    /** Bills already settled on this local day (id → date): a second tap is a no-op even before the database echoes. */
    private val settled = HashMap<String, String>()

    val state: StateFlow<RecurringUiState> =
        combine(money.recurring, money.subscriptions, clock.dayFlow(), tab, paying, ::build)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecurringUiState())

    fun onAction(a: RecurringAction) {
        when (a) {
            is RecurringAction.SelectTab -> tab.value = a.tab
            is RecurringAction.Add -> add(a)
            is RecurringAction.MarkPaid -> markPaid(a.id)
            is RecurringAction.SetReminder -> viewModelScope.launch { money.setRecurringReminder(a.id, a.on) }
            is RecurringAction.Delete -> viewModelScope.launch {
                money.deleteRecurring(a.id)
                queue.send(UiEvent.Snack("Removed"))
            }
        }
    }

    private fun add(a: RecurringAction.Add) {
        val name = a.name.trim()
        if (name.isEmpty() || a.amountPaise <= 0) return
        val kind = tab.value.kind
        viewModelScope.launch {
            val accounts = money.accounts.first()
            money.addRecurring(
                RecurringDraft(
                    name = name,
                    emoji = a.emoji.ifBlank { "💡" },
                    amountPaise = a.amountPaise,
                    day = a.day.coerceIn(1, 28),
                    kind = kind,
                    reminder = true,
                    accountId = accounts.firstOrNull()?.id,
                    toAccountId = if (kind == RecurringKind.Transfer) accounts.getOrNull(1)?.id else null,
                ),
            )
            queue.send(UiEvent.Snack("Recurring ${kind.wire} added"))
        }
    }

    private fun markPaid(id: String) {
        val today = clock.today()
        // Synchronous guards: nothing between the tap and the repository call can let a second tap through.
        if (id in paying.value || settled[id] == today) return
        paying.value = paying.value + id
        viewModelScope.launch {
            try {
                when (val r = money.payRecurring(id)) {
                    is PayResult.Paid -> {
                        settled[id] = today
                        val name = money.recurring.first().firstOrNull { it.id == id }?.name ?: r.transaction.merchant
                        queue.send(UiEvent.Haptic(HapticKind.Confirm))
                        queue.send(UiEvent.Snack("Paid · $name · ${inrPaise(r.transaction.amountPaise)}"))
                    }
                    PayResult.AlreadyPaid -> {
                        settled[id] = today
                        queue.send(UiEvent.Snack("Already paid this month"))
                    }
                    PayResult.NoAccount -> queue.send(UiEvent.Snack("Add an account first"))
                    PayResult.NoDestination ->
                        queue.send(UiEvent.Snack("Set a destination account on this recurring transfer first"))
                    PayResult.NotFound -> Unit
                }
            } finally {
                paying.value = paying.value - id
            }
        }
    }

    private fun build(
        recurring: List<Recurring>,
        subs: List<Subscription>,
        today: String,
        tab: RecurringTab,
        paying: Set<String>,
    ): RecurringUiState {
        fun row(r: Recurring, sub: String, right: String) = RecurringRowUi(
            id = r.id, emoji = r.emoji, name = r.name, sub = sub, amount = inrPaise(r.amountPaise), right = right,
            reminder = r.reminder,
            paidLabel = if (paidThisMonth(r, today)) "Paid ${fmtDay(r.lastPaidOn!!, today)}" else null,
            busy = r.id in paying,
        )
        val soon = dueSoon(recurring, today)
        val unpaid = soon.filterNot { paidThisMonth(it, today) }
        return RecurringUiState(
            loaded = true,
            tab = tab,
            monthTotal = inrPaise(recurringMonthTotal(recurring, subs)),
            monthSub = "${recurring.size + subs.size} items",
            next7 = inrPaise(unpaid.sumOf { it.amountPaise }),
            next7Sub = "${unpaid.size} due",
            dueSoon = soon.map { r ->
                val d = daysToDay(r.day, today)
                val whenText = when (d) { 0 -> "Today"; 1 -> "Tomorrow"; else -> "in $d days" }
                row(r, "$whenText · ${r.method}", if (r.reminder) "Pay reminder set" else "Auto")
            },
            all = recurring.filter { it.kind == tab.kind }.sortedBy { it.day }
                .map { r -> row(r, "${ordinal(r.day)} · ${r.method}", if (r.reminder) "reminder on" else "") },
        )
    }
}
