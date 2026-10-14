package tech.beyondscale.mitra.data.repository

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tech.beyondscale.mitra.data.remote.paiseToRupees
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.CategoryChange
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxDraft
import tech.beyondscale.mitra.domain.model.TxKind

/*
 * Local merges for money edits — the same normalisation the web store and the server apply (zod `.trim()` on
 * merchant and note), so the local row equals what the server will echo.
 */

/** A new transaction from the add sheet. */
fun TxDraft.toTransaction(id: String): Transaction = Transaction(
    id = id, kind = kind, amountPaise = amountPaise, categoryId = categoryId?.ifEmpty { null }, accountId = accountId,
    toAccountId = destination(), merchant = merchant.trim(), note = note?.trim()?.ifEmpty { null }, date = date, time = time, source = source,
)

/** An edit from the sheet: every editable field is replaced; source, recurring link and tags are kept. */
fun Transaction.edited(draft: TxDraft): Transaction = copy(
    kind = draft.kind, amountPaise = draft.amountPaise, categoryId = draft.categoryId?.ifEmpty { null },
    accountId = draft.accountId, toAccountId = draft.destination(), merchant = draft.merchant.trim(),
    note = draft.note?.trim()?.ifEmpty { null }, date = draft.date, time = draft.time,
)

/** The server keeps `toAccountId` only on transfers. */
private fun TxDraft.destination(): String? = if (kind == TxKind.Transfer) toAccountId?.ifEmpty { null } else null

/** `PATCH money/categories/:id` body: only the changed keys; `Budget(null)` clears the budget (`"budget": null`). */
fun categoryPatch(changes: List<CategoryChange>): JsonObject = buildJsonObject {
    changes.forEach { c ->
        when (c) {
            is CategoryChange.Name -> put("name", c.v.trim())
            is CategoryChange.Emoji -> put("emoji", c.v)
            is CategoryChange.Budget -> put("budget", c.paise?.let(::paiseToRupees))
        }
    }
}

/** The local copy of a category after [changes]. */
fun Category.applied(changes: List<CategoryChange>): Category = changes.fold(this) { c, change ->
    when (change) {
        is CategoryChange.Name -> c.copy(name = change.v.trim())
        is CategoryChange.Emoji -> c.copy(emoji = change.v)
        is CategoryChange.Budget -> c.copy(budgetPaise = change.paise)
    }
}
