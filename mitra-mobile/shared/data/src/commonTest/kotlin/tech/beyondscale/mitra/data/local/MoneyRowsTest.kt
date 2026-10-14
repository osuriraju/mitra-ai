package tech.beyondscale.mitra.data.local

import tech.beyondscale.mitra.data.db.Transactions
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.CategoryKind
import tech.beyondscale.mitra.domain.model.Recurring
import tech.beyondscale.mitra.domain.model.RecurringKind
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.domain.model.TxSource
import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyRowsTest {
    @Test fun roundTripsEveryField() {
        val tx = Transaction(
            "t1", TxKind.Transfer, 123_450, "c1", "a1", "a2", "Swiggy", null, "2026-10-14", "09:30",
            TxSource.Ai, "r1", listOf("work", "team"),
        )
        assertEquals(tx, tx.toRow().toDomain())

        val rec = Recurring("r1", "Rent", "🏠", 2_500_000, 5, "UPI", RecurringKind.Bill, true, "c1", "a1", null, null)
        assertEquals(rec, rec.toRow().toDomain())

        val cat = Category("c1", "Food", "🍜", CategoryKind.Expense, "food", null, false)
        assertEquals(cat, cat.toRow(0).toDomain())
    }

    @Test fun unknownWireEnumFallsBack() {
        val row = Transactions("t1", "loan", 1, null, "a1", null, "x", null, "2026-10-14", "00:00", "bot", null, "[]")
        val tx = row.toDomain()
        assertEquals(TxKind.Expense, tx.kind)
        assertEquals(TxSource.User, tx.source)
    }
}
