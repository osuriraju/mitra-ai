package tech.beyondscale.mitra.domain.money

import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.CategoryKind
import tech.beyondscale.mitra.domain.model.Cycle
import tech.beyondscale.mitra.domain.model.Recurring
import tech.beyondscale.mitra.domain.model.RecurringKind
import tech.beyondscale.mitra.domain.model.Subscription
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxKind

/* Categories as mitra-backend/src/money/money-defaults.service.ts seeds them. */
internal fun food(budgetPaise: Long? = null) = Category("food", "Food & dining", "🍛", CategoryKind.Expense, slug = "food", budgetPaise = budgetPaise)
internal fun transport(budgetPaise: Long? = null) = Category("transport", "Transport", "🚇", CategoryKind.Expense, slug = "transport", budgetPaise = budgetPaise)
internal fun home(budgetPaise: Long? = null) = Category("home", "Housing", "🏠", CategoryKind.Expense, slug = "home", budgetPaise = budgetPaise)
internal fun salary(budgetPaise: Long? = null) = Category("salary", "Salary", "💼", CategoryKind.Income, slug = "salary", budgetPaise = budgetPaise)

internal fun tx(
    id: String, paise: Long, date: String = "2026-10-14", kind: TxKind = TxKind.Expense, categoryId: String? = "food",
    accountId: String = "cash", toAccountId: String? = null, merchant: String = "", note: String? = null, time: String = "12:00",
) = Transaction(id, kind, paise, categoryId, accountId, toAccountId, merchant, note, date, time)

internal fun bill(id: String, day: Int, paise: Long = 1_500_000, lastPaidOn: String? = null, name: String = "Rent", emoji: String = "🏠") =
    Recurring(id, name, emoji, paise, day, "UPI", RecurringKind.Bill, reminder = true, categoryId = "home", accountId = "cash", toAccountId = null, lastPaidOn = lastPaidOn)

internal fun sub(id: String, next: String, paise: Long = 64_900, cycle: Cycle = Cycle.Monthly, lastUsed: String? = null, name: String = "Netflix") =
    Subscription(id, name, paise, cycle, next, "#E50914", name.take(1), lastUsed, categoryId = "subs", accountId = "cash")
