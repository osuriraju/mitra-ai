package tech.beyondscale.mitra.domain.money

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.launch
import tech.beyondscale.mitra.core.inr
import tech.beyondscale.mitra.core.inrPaise
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.Recurring
import tech.beyondscale.mitra.domain.model.SessionState
import tech.beyondscale.mitra.domain.model.Subscription
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.User
import kotlin.math.min

/** What the Money widget shows in place of every amount when its "Hide amounts" option is on. */
const val HIDDEN_AMOUNT = "₹••••"

/** The "Safe to spend" home-screen widget (spec §10). Every string is final text: the layout adds only fixed copy. */
sealed interface MoneyWidgetModel {
    /** No account on this device: no data at all, never an amount. */
    data object SignedOut : MoneyWidgetModel

    /** The Money module is turned off in Settings: nothing from Money is shown. */
    data object MoneyOff : MoneyWidgetModel

    /** No category has a budget: "Spent this month [spent]" + "Set budget". */
    data class NoBudget(val spent: String) : MoneyWidgetModel

    data class Budget(
        /** Per day, e.g. "₹1,240" (the layout adds "/ day"). */
        val safe: String,
        /** "₹18,400 of ₹30,000 · 61%" */
        val line: String,
        /** Bar fill 0..1; null when amounts are hidden (the ratio is private too), so no bar is drawn. */
        val progress: Float?,
        val tone: BudgetTone,
        /** "🏠 Rent · in 3 days · ₹15,000"; null when nothing is upcoming. */
        val next: String?,
    ) : MoneyWidgetModel
}

fun moneyWidgetModel(
    categories: List<Category>,
    txs: List<Transaction>,
    recurring: List<Recurring>,
    subs: List<Subscription>,
    today: String,
    signedIn: Boolean,
    hideAmounts: Boolean,
    moneyEnabled: Boolean = true,
): MoneyWidgetModel {
    if (!signedIn) return MoneyWidgetModel.SignedOut
    if (!moneyEnabled) return MoneyWidgetModel.MoneyOff
    val m = todayMoney(categories, txs, recurring, subs, today)
    fun amount(paise: Long) = if (hideAmounts) HIDDEN_AMOUNT else inrPaise(paise)
    if (m.budgetPaise == 0L) return MoneyWidgetModel.NoBudget(amount(m.spentPaise))
    val next = m.next?.let { "${it.emoji} ${it.name} · ${nextLabel(it)} · ${amount(it.amountPaise)}" }
    return MoneyWidgetModel.Budget(
        safe = if (hideAmounts) HIDDEN_AMOUNT else inr(m.safePerDayRupees ?: 0.0),
        line = if (hideAmounts) {
            "$HIDDEN_AMOUNT of $HIDDEN_AMOUNT"
        } else {
            "${inrPaise(m.spentPaise)} of ${inrPaise(m.budgetPaise)} · ${budgetPct(m.spentPaise, m.budgetPaise)}%"
        },
        progress = if (hideAmounts) null else min(1.0, m.spentPaise.toDouble() / m.budgetPaise).toFloat(),
        // Hidden: the tone (near / over budget) is as private as the numbers.
        tone = if (hideAmounts) BudgetTone.Normal else budgetTone(m.spentPaise, m.budgetPaise),
        next = next,
    )
}

/* ---------- inputs (shared by the widget and its updater) ---------- */

/**
 * Whether the widget may show this account's data: a cached user whose session has not ended. After a 401 the app
 * shows Login but keeps the cached user (for same-user re-login), so [SessionState.SignedOut] hides everything.
 * [SessionState.Unknown] (a widget-only process that never ran restore) trusts the cache.
 */
fun widgetSignedIn(user: User?, session: SessionState): Boolean = user != null && session !is SessionState.SignedOut

/** Everything the widget model is computed from, except the per-widget "Hide amounts". */
data class MoneyWidgetInputs(
    val categories: List<Category>,
    val txs: List<Transaction>,
    val recurring: List<Recurring>,
    val subs: List<Subscription>,
    val today: String,
    val userId: String?,
    val signedIn: Boolean,
    val moneyEnabled: Boolean,
) {
    fun model(hideAmounts: Boolean): MoneyWidgetModel =
        moneyWidgetModel(categories, txs, recurring, subs, today, signedIn, hideAmounts, moneyEnabled)
}

/**
 * How long after the local user changes (an account switch, or sign-in) list values are held back. Switching
 * accounts wipes and re-caches in one DB transaction, but the user row and the money lists re-query independently,
 * so the new user can arrive while the lists still hold the previous account's rows; within this window both have
 * re-queried. The widget keeps its previous frame meanwhile (SignedOut: an account switch always passes through an
 * ended session or a sign-out).
 */
const val ACCOUNT_SWITCH_SETTLE_MS = 300L

@OptIn(ExperimentalCoroutinesApi::class)
fun moneyWidgetInputs(
    categories: Flow<List<Category>>,
    txs: Flow<List<Transaction>>,
    recurring: Flow<List<Recurring>>,
    subs: Flow<List<Subscription>>,
    localUser: Flow<User?>,
    session: Flow<SessionState>,
    day: Flow<String>,
): Flow<MoneyWidgetInputs> {
    val lists = combine(categories, txs, recurring, subs) { c, t, r, s ->
        MoneyWidgetInputs(c, t, r, s, today = "", userId = null, signedIn = false, moneyEnabled = true)
    }
    val current = combine(lists, localUser, session, day) { d, user, sess, today ->
        d.copy(
            today = today,
            userId = user?.id,
            signedIn = widgetSignedIn(user, sess),
            moneyEnabled = user?.settings?.modules?.money ?: true,
        )
    }
    // Signing out (no user) is never held: SignedOut must show at once.
    return localUser.map { it?.id }.distinctUntilChanged().withIndex().flatMapLatest { (i, id) ->
        if (i == 0 || id == null) current else current.heldFor(ACCOUNT_SWITCH_SETTLE_MS)
    }
}

/** Emits nothing for [ms], then the latest value, then everything as it comes. */
private fun <T> Flow<T>.heldFor(ms: Long): Flow<T> = channelFlow {
    val gate = launch { delay(ms) }
    collectLatest { value ->
        gate.join()
        send(value)
    }
}
