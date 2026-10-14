package tech.beyondscale.mitra.domain.model

/* Money models. Every amount is integer paise (`*Paise: Long`) on the device; the server speaks rupees. */

enum class AccountKind(val wire: String) { Bank("bank"), Credit("credit"), Cash("cash") }

data class Account(
    val id: String,
    val name: String,
    val mask: String,
    val kind: AccountKind,
    val balancePaise: Long,
    val archived: Boolean = false,
)

enum class CategoryKind(val wire: String) { Expense("expense"), Income("income") }

/** `slug` is set on the system categories every user starts with (food, transport, transfer, salary…); custom ones have none. */
data class Category(
    val id: String,
    val name: String,
    val emoji: String,
    val kind: CategoryKind,
    val slug: String? = null,
    val budgetPaise: Long? = null,
    val archived: Boolean = false,
)

enum class TxKind(val wire: String) { Expense("expense"), Income("income"), Transfer("transfer") }

enum class TxSource(val wire: String) { User("user"), Import("import"), Ai("ai"), Recurring("recurring") }

data class Transaction(
    val id: String,
    val kind: TxKind,
    val amountPaise: Long,
    val categoryId: String?,
    val accountId: String,
    val toAccountId: String?,
    val merchant: String,
    val note: String?,
    /** Local ISO date, "2026-10-14". */
    val date: String,
    /** "HH:MM". */
    val time: String,
    val source: TxSource = TxSource.User,
    val recurringId: String? = null,
    val tags: List<String> = emptyList(),
)

/** What the add/edit sheet produces; the repository assigns the id. */
data class TxDraft(
    val kind: TxKind,
    val amountPaise: Long,
    val categoryId: String?,
    val accountId: String,
    val toAccountId: String?,
    val merchant: String,
    val note: String?,
    val date: String,
    val time: String,
    val source: TxSource = TxSource.User,
)

enum class RecurringKind(val wire: String) { Bill("bill"), Income("income"), Transfer("transfer") }

data class Recurring(
    val id: String,
    val name: String,
    val emoji: String,
    val amountPaise: Long,
    /** Day of the month it falls due (1..28 when created in the app). */
    val day: Int,
    val method: String,
    val kind: RecurringKind,
    val reminder: Boolean,
    val categoryId: String?,
    val accountId: String?,
    val toAccountId: String?,
    /** Local ISO date of the last "Mark paid". */
    val lastPaidOn: String?,
)

data class RecurringDraft(
    val name: String,
    val emoji: String,
    val amountPaise: Long,
    val day: Int,
    val kind: RecurringKind,
    val reminder: Boolean = true,
    val categoryId: String? = null,
    val accountId: String? = null,
    val toAccountId: String? = null,
)

enum class Cycle(val wire: String) { Monthly("Monthly"), Yearly("Yearly") }

data class Subscription(
    val id: String,
    val name: String,
    val amountPaise: Long,
    val cycle: Cycle,
    /** Next renewal, local ISO date. */
    val next: String,
    val color: String,
    val letter: String,
    val lastUsed: String?,
    val categoryId: String?,
    val accountId: String?,
)

/** One field of a category edit; only the fields present are sent. */
sealed interface CategoryChange {
    data class Name(val v: String) : CategoryChange
    data class Emoji(val v: String) : CategoryChange
    /** `Budget(null)` removes the budget (wire `budget: null`). */
    data class Budget(val paise: Long?) : CategoryChange
}
