package tech.beyondscale.mitra.data.repository

import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxDraft
import tech.beyondscale.mitra.domain.model.TxKind

/*
 * Local merges for money edits — the same normalisation the web store and the server apply, so the local row equals
 * what the server will echo.
 */

/** A new transaction from the add sheet. */
fun TxDraft.toTransaction(id: String): Transaction = Transaction(
    id = id, kind = kind, amountPaise = amountPaise, categoryId = categoryId?.ifEmpty { null }, accountId = accountId,
    toAccountId = destination(), merchant = merchant, note = note?.ifBlank { null }, date = date, time = time, source = source,
)

/** An edit from the sheet: every editable field is replaced; source, recurring link and tags are kept. */
fun Transaction.edited(draft: TxDraft): Transaction = copy(
    kind = draft.kind, amountPaise = draft.amountPaise, categoryId = draft.categoryId?.ifEmpty { null },
    accountId = draft.accountId, toAccountId = draft.destination(), merchant = draft.merchant,
    note = draft.note?.ifBlank { null }, date = draft.date, time = draft.time,
)

/** The server keeps `toAccountId` only on transfers. */
private fun TxDraft.destination(): String? = if (kind == TxKind.Transfer) toAccountId?.ifEmpty { null } else null
