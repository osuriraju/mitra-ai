package tech.beyondscale.mitra.domain.money

import tech.beyondscale.mitra.core.inr
import tech.beyondscale.mitra.core.inrPaise
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.Recurring
import tech.beyondscale.mitra.domain.model.Subscription
import tech.beyondscale.mitra.domain.model.Transaction
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
        tone = budgetTone(m.spentPaise, m.budgetPaise),
        next = next,
    )
}
