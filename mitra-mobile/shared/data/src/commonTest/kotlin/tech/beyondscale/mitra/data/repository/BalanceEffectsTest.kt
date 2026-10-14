package tech.beyondscale.mitra.data.repository

import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxKind
import kotlin.test.Test
import kotlin.test.assertEquals

class BalanceEffectsTest {
    private fun tx(kind: TxKind, paise: Long, to: String? = null) = Transaction("t1", kind, paise, null, "A", to, "", null, "2026-10-14", "10:00")

    @Test fun transferMovesBetweenAccounts() {
        assertEquals(listOf("A" to -50000L, "B" to 50000L), balanceEffects(tx(TxKind.Transfer, 50000, "B")))
        assertEquals(listOf("A" to 100000L), balanceEffects(tx(TxKind.Income, 100000)))
        assertEquals(listOf("A" to -45000L), balanceEffects(tx(TxKind.Expense, 45000, "B")))
    }
}
