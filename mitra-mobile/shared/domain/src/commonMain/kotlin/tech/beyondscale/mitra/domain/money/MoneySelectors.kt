package tech.beyondscale.mitra.domain.money

import tech.beyondscale.mitra.core.addDays
import tech.beyondscale.mitra.core.dayOfMonth
import tech.beyondscale.mitra.core.daysInMonth
import tech.beyondscale.mitra.core.diffDays
import tech.beyondscale.mitra.core.jsRound
import tech.beyondscale.mitra.core.monthKey
import tech.beyondscale.mitra.domain.model.Account
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.CategoryKind
import tech.beyondscale.mitra.domain.model.Cycle
import tech.beyondscale.mitra.domain.model.Recurring
import tech.beyondscale.mitra.domain.model.RecurringKind
import tech.beyondscale.mitra.domain.model.Subscription
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxKind
import kotlin.math.max

/*
 * Ports of the money selectors in mitra-frontend/src/store/selectors.ts and the helpers inlined in
 * screens/money.tsx. Amounts are paise (Long); "today" is always the caller's local date.
 */

/** [spentByCategory] key for expenses without a (known) category. */
const val UNCATEGORISED = "uncategorised"

fun thisMonth(txs: List<Transaction>, month: String): List<Transaction> = txs.filter { monthKey(it.date) == month }

private fun List<Transaction>.totalOf(kind: TxKind): Long = filter { it.kind == kind }.sumOf { it.amountPaise }

fun spent(txs: List<Transaction>): Long = txs.totalOf(TxKind.Expense)
fun income(txs: List<Transaction>): Long = txs.totalOf(TxKind.Income)
fun transferred(txs: List<Transaction>): Long = txs.totalOf(TxKind.Transfer)
fun spentToday(txs: List<Transaction>, today: String): Long = spent(txs.filter { it.date == today })

fun spentByCategory(txs: List<Transaction>): Map<String, Long> {
    val m = LinkedHashMap<String, Long>()
    txs.filter { it.kind == TxKind.Expense }.forEach { val k = it.categoryId ?: UNCATEGORISED; m[k] = (m[k] ?: 0L) + it.amountPaise }
    return m
}

/** Sum of the monthly budgets on (non-archived) expense categories. */
fun totalBudget(categories: List<Category>): Long =
    categories.filter { it.kind == CategoryKind.Expense && !it.archived }.sumOf { it.budgetPaise ?: 0L }

fun netWorth(accounts: List<Account>): Long = accounts.sumOf { it.balancePaise }

/* ---------- budgets ---------- */

enum class BudgetTone { Normal, Warning, Danger }

/** Spec §10: danger over 100 %, warning from 90 %. */
fun budgetTone(spentPaise: Long, budgetPaise: Long): BudgetTone = when {
    spentPaise > budgetPaise -> BudgetTone.Danger
    spentPaise * 10 >= budgetPaise * 9 -> BudgetTone.Warning
    else -> BudgetTone.Normal
}

/** Whole-number percentage used, `Math.round` like the web; 0 without a budget. */
fun budgetPct(spentPaise: Long, budgetPaise: Long): Int =
    if (budgetPaise == 0L) 0 else jsRound(spentPaise * 100.0 / budgetPaise).toInt()

/* ---------- dashboard ---------- */

/** The month picker's options: the current month plus every month with a transaction, newest first. */
fun monthsWithData(txs: List<Transaction>, today: String): List<String> =
    (listOf(monthKey(today)) + txs.map { monthKey(it.date) }).distinct().sortedDescending()

data class DayPoint(val date: String, val paise: Long, val label: String)

/** Spend per day of [tx] (already filtered to [month]): the last 16 days ending today for the current month, every day for a past month. */
fun dailySpend(tx: List<Transaction>, month: String, today: String): List<DayPoint> {
    val cur = month == monthKey(today)
    val dim = daysInMonth("$month-01")
    val last = if (cur) today else "$month-${dim.toString().padStart(2, '0')}"
    val days = if (cur) 16 else dim
    val step = if (cur) 3 else 5
    return List(days) { i ->
        val d = addDays(last, -(days - 1 - i))
        DayPoint(d, spent(tx.filter { it.date == d }), if (i % step == 0 || i == days - 1) dayOfMonth(d).toString() else "")
    }
}

/** Spend-rate projection to month end, rounded to whole rupees like the web (returned in paise). */
fun projectedMonthEnd(spentPaise: Long, month: String, today: String): Long {
    val dim = daysInMonth("$month-01")
    val day = if (month == monthKey(today)) dayOfMonth(today) else dim
    return jsRound(spentPaise / 100.0 / max(1, day) * dim) * 100
}

fun recentTransactions(tx: List<Transaction>, n: Int = 5): List<Transaction> =
    tx.sortedByDescending { it.date + it.time }.take(n)

/* ---------- analytics ---------- */

data class CatSpend(val id: String, val name: String, val emoji: String, val paise: Long, val count: Int)

/** Expenses per category, largest first; unknown or missing categories fold into "Uncategorised". */
fun categoryBreakdown(tx: List<Transaction>, categories: List<Category>): List<CatSpend> {
    val m = LinkedHashMap<String, CatSpend>()
    tx.filter { it.kind == TxKind.Expense }.forEach { t ->
        val c = categories.firstOrNull { it.id == t.categoryId }
        val k = c?.id ?: UNCATEGORISED
        val row = m[k] ?: CatSpend(k, c?.name ?: "Uncategorised", c?.emoji ?: "💸", 0, 0)
        m[k] = row.copy(paise = row.paise + t.amountPaise, count = row.count + 1)
    }
    return m.values.sortedByDescending { it.paise }
}

data class AccountSpend(val account: Account, val paise: Long, val count: Int)

/** Money leaving each account (expenses + transfers out), accounts with outflows only, largest first. */
fun accountUtilisation(tx: List<Transaction>, accounts: List<Account>): List<AccountSpend> {
    val out = tx.filter { it.kind != TxKind.Income }
    return accounts.map { a -> out.filter { it.accountId == a.id }.let { AccountSpend(a, it.sumOf { t -> t.amountPaise }, it.size) } }
        .filter { it.count > 0 }
        .sortedByDescending { it.paise }
}

fun topOutflows(tx: List<Transaction>, n: Int = 5): List<Transaction> =
    tx.filter { it.kind == TxKind.Expense }.sortedByDescending { it.amountPaise }.take(n)

/** (income − spent) / income × 100; null without income (the web shows "—"). */
fun savingsRatePct(incomePaise: Long, spentPaise: Long): Double? =
    if (incomePaise == 0L) null else (incomePaise - spentPaise).toDouble() / incomePaise * 100

/* ---------- transactions list ---------- */

data class TxFilter(
    /** null = all time. */
    val month: String? = null,
    val categoryId: String? = null,
    val accountId: String? = null,
    val kind: TxKind? = null,
    val query: String = "",
)

enum class TxSort { Date, Amount }

fun filterTransactions(txs: List<Transaction>, filter: TxFilter, sort: TxSort): List<Transaction> {
    val q = filter.query.lowercase()
    val list = txs.filter {
        (filter.month == null || monthKey(it.date) == filter.month) &&
            (filter.categoryId == null || it.categoryId == filter.categoryId) &&
            (filter.accountId == null || it.accountId == filter.accountId) &&
            (filter.kind == null || it.kind == filter.kind) &&
            (q.isEmpty() || (it.merchant + " " + (it.note ?: "")).lowercase().contains(q))
    }
    return when (sort) {
        TxSort.Date -> list.sortedByDescending { it.date + it.time }
        TxSort.Amount -> list.sortedByDescending { it.amountPaise }
    }
}

data class TxGroup(val title: String, val items: List<Transaction>, val netPaise: Long)

/** Today · Yesterday · Earlier · Upcoming, empty groups dropped; net = income − expenses (transfers count 0). */
fun groupTransactions(list: List<Transaction>, today: String): List<TxGroup> {
    val yesterday = addDays(today, -1)
    return listOf(
        "Today" to list.filter { it.date == today },
        "Yesterday" to list.filter { it.date == yesterday },
        "Earlier" to list.filter { it.date < yesterday },
        "Upcoming" to list.filter { it.date > today },
    ).filter { it.second.isNotEmpty() }.map { (title, items) ->
        TxGroup(title, items, items.sumOf { if (it.kind == TxKind.Income) it.amountPaise else if (it.kind == TxKind.Expense) -it.amountPaise else 0L })
    }
}

/* ---------- add sheet ---------- */

/** An existing transaction with the same merchant (case-insensitive, trimmed), amount and date. */
fun findDuplicate(txs: List<Transaction>, merchant: String, amountPaise: Long, date: String): Transaction? {
    val m = merchant.trim().lowercase()
    if (m.isEmpty()) return null
    return txs.firstOrNull { it.merchant.lowercase() == m && it.amountPaise == amountPaise && it.date == date }
}

/** Up to three distinct merchants used with this category, in list order (newest first). */
fun recentMerchants(txs: List<Transaction>, categoryId: String?): List<String> =
    txs.filter { it.categoryId == categoryId && it.merchant.isNotEmpty() }.map { it.merchant }.distinct().take(3)

/* ---------- recurring & subscriptions ---------- */

/** Days until day-of-month [day] comes round again (0 = today), wrapping across the month end. */
fun daysToDay(day: Int, today: String): Int {
    val dim = daysInMonth(today)
    return (day - dayOfMonth(today) + dim) % dim
}

fun paidThisMonth(r: Recurring, today: String): Boolean = r.lastPaidOn != null && monthKey(r.lastPaidOn) == monthKey(today)

/** Outgoing recurring items (bills + transfers) plus monthly subscriptions. */
fun recurringMonthTotal(recurring: List<Recurring>, subs: List<Subscription>): Long =
    recurring.filter { it.kind != RecurringKind.Income }.sumOf { it.amountPaise } +
        subs.filter { it.cycle == Cycle.Monthly }.sumOf { it.amountPaise }

/** Bills due within 7 days, soonest first (paid ones included — the row shows "Paid"). */
fun dueSoon(recurring: List<Recurring>, today: String): List<Recurring> =
    recurring.filter { it.kind == RecurringKind.Bill && daysToDay(it.day, today) <= 7 }.sortedBy { daysToDay(it.day, today) }

data class Upcoming(val id: String, val emoji: String, val name: String, val amountPaise: Long, val days: Int, val isSubscription: Boolean)

/** The dashboard's "Upcoming": bills and subscription renewals, soonest first (paid bills are not excluded, as on the web). */
fun upcoming(recurring: List<Recurring>, subs: List<Subscription>, today: String, n: Int = 3): List<Upcoming> =
    (
        recurring.filter { it.kind == RecurringKind.Bill }.map { Upcoming(it.id, it.emoji, it.name, it.amountPaise, daysToDay(it.day, today), false) } +
            subs.map { Upcoming(it.id, "🔁", it.name, it.amountPaise, diffDays(it.next, today), true) }
        ).filter { it.days >= 0 }.sortedBy { it.days }.take(n)

/** Monthly cost of all subscriptions (yearly ones / 12), in paise — fractional like the web. */
fun subscriptionsMonthlyPaise(subs: List<Subscription>): Double =
    subs.sumOf { if (it.cycle == Cycle.Monthly) it.amountPaise.toDouble() else it.amountPaise / 12.0 }

fun unusedSubscriptions(subs: List<Subscription>, today: String): List<Subscription> =
    subs.filter { it.lastUsed != null && diffDays(today, it.lastUsed) > 30 }

/** Renewals within the next 30 days (anything already past included, as on the web), soonest first. */
fun renewalsWithin30(subs: List<Subscription>, today: String): List<Subscription> =
    subs.sortedBy { it.next }.filter { diffDays(it.next, today) <= 30 }
