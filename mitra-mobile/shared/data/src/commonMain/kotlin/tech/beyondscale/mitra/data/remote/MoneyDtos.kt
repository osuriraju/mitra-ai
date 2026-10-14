package tech.beyondscale.mitra.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import tech.beyondscale.mitra.core.jsRound
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

/*
 * Shapes of mitra-backend/src/money/mapper.ts (`undefined` fields are simply absent). The server speaks rupees as JSON
 * numbers; the device keeps integer paise. This file is the only place the two meet.
 */

/** Rupees → paise with the server's `toMinor` rounding (`Math.round(rupees * 100)`): 4.35 → 435, 1234.1 → 123410. */
internal fun rupeesToPaise(rupees: Double): Long = jsRound(rupees * 100)

/** Paise → rupees for request bodies: 435 → 4.35 (the server's amount check tolerates the binary representation). */
internal fun paiseToRupees(paise: Long): Double = paise / 100.0

/** The web sends `x || null`: an empty reference id is no reference. */
private fun String?.ref(): String? = this?.ifEmpty { null }

private inline fun <reified E : Enum<E>> wireEnum(wire: String, wireOf: (E) -> String): E {
    val all = enumValues<E>()
    return all.firstOrNull { wireOf(it) == wire } ?: all.first()
}

@Serializable
data class AccountDto(
    val id: String,
    val name: String,
    val mask: String = "",
    val kind: String = "bank",
    /** Rupees. */
    val balance: Double = 0.0,
    val archived: Boolean = false,
)

@Serializable
data class CategoryDto(
    val id: String,
    val name: String,
    val emoji: String = "💸",
    val kind: String = "expense",
    val slug: String? = null,
    /** Rupees. */
    val budget: Double? = null,
    val archived: Boolean = false,
)

@Serializable
data class TransactionDto(
    val id: String,
    val kind: String = "expense",
    /** Rupees. */
    val amount: Double,
    val categoryId: String? = null,
    val accountId: String,
    val toAccountId: String? = null,
    val merchant: String = "",
    val note: String? = null,
    val date: String,
    val time: String = "00:00",
    val source: String = "user",
    val recurringId: String? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class RecurringDto(
    val id: String,
    val name: String,
    val emoji: String = "🔁",
    /** Rupees. */
    val amount: Double,
    val day: Int,
    val method: String = "UPI",
    val kind: String = "bill",
    val reminder: Boolean = false,
    val categoryId: String? = null,
    val accountId: String? = null,
    val toAccountId: String? = null,
    val lastPaidOn: String? = null,
)

@Serializable
data class SubscriptionDto(
    val id: String,
    val name: String,
    /** Rupees. */
    val amount: Double,
    val cycle: String = "Monthly",
    val next: String,
    val color: String = "#6366F1",
    val letter: String = "",
    val lastUsed: String? = null,
    val categoryId: String? = null,
    val accountId: String? = null,
)

/** `GET money/bootstrap`. */
@Serializable
data class MoneyBootstrapDto(
    val accounts: List<AccountDto> = emptyList(),
    val categories: List<CategoryDto> = emptyList(),
    val transactions: List<TransactionDto> = emptyList(),
    val recurring: List<RecurringDto> = emptyList(),
    val subscriptions: List<SubscriptionDto> = emptyList(),
)

/** `POST money/recurring/:id/pay`. */
@Serializable
data class PayResultDto(val transaction: TransactionDto, val recurring: RecurringDto)

fun AccountDto.toDomain(): Account =
    Account(id, name, mask, wireEnum<AccountKind>(kind) { it.wire }, rupeesToPaise(balance), archived)

fun CategoryDto.toDomain(): Category = Category(
    id, name, emoji, wireEnum<CategoryKind>(kind) { it.wire }, slug, budget?.let(::rupeesToPaise), archived,
)

fun TransactionDto.toDomain(): Transaction = Transaction(
    id = id, kind = wireEnum<TxKind>(kind) { it.wire }, amountPaise = rupeesToPaise(amount), categoryId = categoryId.ref(),
    accountId = accountId, toAccountId = toAccountId.ref(), merchant = merchant, note = note, date = date, time = time,
    source = wireEnum<TxSource>(source) { it.wire }, recurringId = recurringId.ref(), tags = tags,
)

fun RecurringDto.toDomain(): Recurring = Recurring(
    id = id, name = name, emoji = emoji, amountPaise = rupeesToPaise(amount), day = day, method = method,
    kind = wireEnum<RecurringKind>(kind) { it.wire }, reminder = reminder, categoryId = categoryId.ref(),
    accountId = accountId.ref(), toAccountId = toAccountId.ref(), lastPaidOn = lastPaidOn,
)

fun SubscriptionDto.toDomain(): Subscription = Subscription(
    id, name, rupeesToPaise(amount), wireEnum<Cycle>(cycle) { it.wire }, next, color, letter, lastUsed,
    categoryId.ref(), accountId.ref(),
)

/** `toAccountId` only travels with a transfer (the server nulls it otherwise). */
private fun Transaction.destination(): String? = if (kind == TxKind.Transfer) toAccountId.ref() else null

/**
 * `POST money/transactions` body, as the web's `addTransaction` sends it: every field present, empty references and a
 * blank note as JSON null.
 */
fun Transaction.createBody(): JsonObject = buildJsonObject {
    put("id", id)
    put("kind", kind.wire)
    put("amount", paiseToRupees(amountPaise))
    put("categoryId", categoryId.ref())
    put("accountId", accountId)
    put("toAccountId", destination())
    put("merchant", merchant)
    put("note", note?.ifBlank { null })
    put("date", date)
    put("time", time)
    put("source", source.wire)
    put("recurringId", recurringId.ref())
    putJsonArray("tags") { tags.forEach { add(JsonPrimitive(it)) } }
}

/** `PATCH money/transactions/:id` body: the web's `updateTransaction` sends every editable field. */
fun Transaction.patchBody(): JsonObject = buildJsonObject {
    put("kind", kind.wire)
    put("amount", paiseToRupees(amountPaise))
    put("categoryId", categoryId.ref())
    put("accountId", accountId)
    put("toAccountId", destination())
    put("merchant", merchant)
    put("note", note?.ifBlank { null })
    put("date", date)
    put("time", time)
}
