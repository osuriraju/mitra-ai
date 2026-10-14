package tech.beyondscale.mitra.data.local

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import tech.beyondscale.mitra.data.db.Accounts
import tech.beyondscale.mitra.data.db.Categories
import tech.beyondscale.mitra.data.db.Subscriptions
import tech.beyondscale.mitra.data.db.Transactions
import tech.beyondscale.mitra.domain.model.Account
import tech.beyondscale.mitra.domain.model.AccountKind
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.CategoryKind
import tech.beyondscale.mitra.domain.model.Cycle
import tech.beyondscale.mitra.domain.model.Recurring
import tech.beyondscale.mitra.domain.model.RecurringKind
import tech.beyondscale.mitra.domain.model.Subscription
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.domain.model.TxSource
import tech.beyondscale.mitra.data.db.Recurring as RecurringRow

private val tagsSerializer = ListSerializer(String.serializer())

/** Unknown wire values fall back to the first constant instead of throwing. */
private inline fun <reified E : Enum<E>> enumFrom(wire: String, wireOf: (E) -> String): E {
    val all = enumValues<E>()
    return all.firstOrNull { wireOf(it) == wire } ?: all.first()
}

private fun decodeTags(s: String): List<String> =
    runCatching { Json.decodeFromString(tagsSerializer, s) }.getOrDefault(emptyList())

private fun Boolean.bit(): Long = if (this) 1L else 0L

fun Accounts.toDomain(): Account =
    Account(id, name, mask, enumFrom<AccountKind>(kind) { it.wire }, balancePaise, archived != 0L)

fun Account.toRow(position: Long): Accounts =
    Accounts(id, name, mask, kind.wire, balancePaise, archived.bit(), position)

fun Categories.toDomain(): Category =
    Category(id, name, emoji, enumFrom<CategoryKind>(kind) { it.wire }, slug, budgetPaise, archived != 0L)

fun Category.toRow(position: Long): Categories =
    Categories(id, name, emoji, kind.wire, slug, budgetPaise, archived.bit(), position)

fun Transactions.toDomain(): Transaction = Transaction(
    id = id, kind = enumFrom<TxKind>(kind) { it.wire }, amountPaise = amountPaise, categoryId = categoryId,
    accountId = accountId, toAccountId = toAccountId, merchant = merchant, note = note, date = date, time = time,
    source = enumFrom<TxSource>(source) { it.wire }, recurringId = recurringId, tags = decodeTags(tags),
)

fun Transaction.toRow(): Transactions = Transactions(
    id, kind.wire, amountPaise, categoryId, accountId, toAccountId, merchant, note, date, time, source.wire,
    recurringId, Json.encodeToString(tagsSerializer, tags),
)

fun RecurringRow.toDomain(): Recurring = Recurring(
    id = id, name = name, emoji = emoji, amountPaise = amountPaise, day = day.toInt(), method = method,
    kind = enumFrom<RecurringKind>(kind) { it.wire }, reminder = reminder != 0L, categoryId = categoryId,
    accountId = accountId, toAccountId = toAccountId, lastPaidOn = lastPaidOn,
)

fun Recurring.toRow(): RecurringRow = RecurringRow(
    id, name, emoji, amountPaise, day.toLong(), method, kind.wire, reminder.bit(), categoryId, accountId,
    toAccountId, lastPaidOn,
)

fun Subscriptions.toDomain(): Subscription = Subscription(
    id, name, amountPaise, enumFrom<Cycle>(cycle) { it.wire }, next, color, letter, lastUsed, categoryId, accountId,
)

fun Subscription.toRow(): Subscriptions =
    Subscriptions(id, name, amountPaise, cycle.wire, next, color, letter, lastUsed, categoryId, accountId)
