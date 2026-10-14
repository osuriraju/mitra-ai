package tech.beyondscale.mitra.data.repository

import tech.beyondscale.mitra.data.db.MitraDb
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxKind

/**
 * How a transaction moves account balances — port of `effects` in mitra-backend/src/money/transactions.service.ts:
 * an expense takes from its account, income adds to it, a transfer moves the amount from `accountId` to `toAccountId`.
 */
fun balanceEffects(tx: Transaction): List<Pair<String, Long>> = when (tx.kind) {
    TxKind.Expense -> listOf(tx.accountId to -tx.amountPaise)
    TxKind.Income -> listOf(tx.accountId to tx.amountPaise)
    TxKind.Transfer -> listOfNotNull(tx.accountId to -tx.amountPaise, tx.toAccountId?.let { it to tx.amountPaise })
}

/** Applies ([sign] = 1) or reverses ([sign] = -1) [fx] on the local account balances. Call inside a transaction. */
fun MitraDb.applyEffects(fx: List<Pair<String, Long>>, sign: Int) {
    fx.forEach { (accountId, delta) -> accountsQueries.adjustBalance(sign * delta, accountId) }
}
