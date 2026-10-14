package tech.beyondscale.mitra.domain.money

import tech.beyondscale.mitra.core.MON
import tech.beyondscale.mitra.core.dayOfMonth
import tech.beyondscale.mitra.core.daysInMonth
import tech.beyondscale.mitra.core.diffDays
import tech.beyondscale.mitra.core.inr
import tech.beyondscale.mitra.core.inrPaise
import tech.beyondscale.mitra.core.jsRound
import tech.beyondscale.mitra.core.monthKey
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.Recurring
import tech.beyondscale.mitra.domain.model.RecurringKind
import tech.beyondscale.mitra.domain.model.Subscription
import tech.beyondscale.mitra.domain.model.Transaction
import kotlin.math.max

/* Port of TodayMoney in mitra-frontend/src/screens/today.tsx — the Today Money card and the Money widget. */

data class BudgetAlert(val emoji: String, val name: String, val over: Boolean, val text: String)

data class NextBill(val emoji: String, val name: String, val amountPaise: Long, val days: Int)

data class TodayMoney(
    val budgetPaise: Long,
    val spentPaise: Long,
    /** budget − spent; negative when over budget. */
    val remainingPaise: Long,
    /** Rupees per day left to spend (≥ 0), null without a budget. */
    val safePerDayRupees: Double?,
    /** Days left in the month, today included (≥ 1). */
    val daysLeft: Int,
    /** "Oct" */
    val monthShort: String,
    val alert: BudgetAlert?,
    val next: NextBill?,
)

fun todayMoney(
    categories: List<Category>,
    txs: List<Transaction>,
    recurring: List<Recurring>,
    subs: List<Subscription>,
    today: String,
): TodayMoney {
    val tx = thisMonth(txs, monthKey(today))
    val sp = spent(tx)
    val budget = totalBudget(categories)
    val by = spentByCategory(tx)
    val daysLeft = daysInMonth(today) - dayOfMonth(today) + 1 // today counts
    val remaining = budget - sp
    return TodayMoney(
        budgetPaise = budget,
        spentPaise = sp,
        remainingPaise = remaining,
        safePerDayRupees = if (budget == 0L) null else max(0.0, remaining / 100.0 / daysLeft),
        daysLeft = daysLeft,
        monthShort = MON[today.substring(5, 7).toInt() - 1],
        alert = budgetAlert(categories, by),
        next = nextBill(recurring, subs, today),
    )
}

private fun budgetAlert(categories: List<Category>, by: Map<String, Long>): BudgetAlert? {
    val budgeted = categories.filter { !it.archived && (it.budgetPaise ?: 0L) != 0L }
    fun spentOn(c: Category) = by[c.id] ?: 0L
    val over = budgeted.filter { spentOn(it) > it.budgetPaise!! }.maxByOrNull { spentOn(it) - it.budgetPaise!! }
    if (over != null) {
        return BudgetAlert(over.emoji, over.name, over = true, text = "${over.emoji} ${over.name} over by ${inrPaise(spentOn(over) - over.budgetPaise!!)}")
    }
    fun ratio(c: Category) = spentOn(c).toDouble() / c.budgetPaise!!
    val near = budgeted.filter { ratio(it) >= 0.9 }.maxByOrNull { ratio(it) } ?: return null
    return BudgetAlert(near.emoji, near.name, over = false, text = "${near.emoji} ${near.name} at ${jsRound(ratio(near) * 100)}% of its budget")
}

/** The next unpaid bill or subscription renewal (bills already paid this month are skipped). */
fun nextBill(recurring: List<Recurring>, subs: List<Subscription>, today: String): NextBill? =
    (
        recurring.filter { it.kind == RecurringKind.Bill && !paidThisMonth(it, today) }
            .map { NextBill(it.emoji, it.name, it.amountPaise, daysToDay(it.day, today)) } +
            subs.map { NextBill("🔁", it.name, it.amountPaise, diffDays(it.next, today)) }
        ).filter { it.days >= 0 }.minByOrNull { it.days }

/** "₹1,240" per day, or "—" without a budget. */
fun safeLabel(m: TodayMoney): String = m.safePerDayRupees?.let(::inr) ?: "—"

/** "18 days left in Oct" · "1 day left in Oct" · "Set budgets to see this". */
fun daysLeftLabel(m: TodayMoney): String =
    if (m.budgetPaise == 0L) "Set budgets to see this" else "${m.daysLeft} day${if (m.daysLeft == 1) "" else "s"} left in ${m.monthShort}"

fun remainingTitle(m: TodayMoney): String = if (m.remainingPaise >= 0) "Left this month" else "Over budget"

/** "today" · "tomorrow" · "in 3 days" */
fun nextLabel(n: NextBill): String = when (n.days) {
    0 -> "today"
    1 -> "tomorrow"
    else -> "in ${n.days} days"
}
