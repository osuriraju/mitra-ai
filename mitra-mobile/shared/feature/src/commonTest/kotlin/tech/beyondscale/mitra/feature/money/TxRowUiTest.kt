package tech.beyondscale.mitra.feature.money

import tech.beyondscale.mitra.domain.model.Account
import tech.beyondscale.mitra.domain.model.AccountKind
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.CategoryKind
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxKind
import tech.beyondscale.mitra.domain.model.TxSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TxRowUiTest {
    private val today = "2026-10-14"
    private val food = Category("food", "Food & dining", "🍜", CategoryKind.Expense)
    private val salary = Category("salary", "Salary", "💼", CategoryKind.Income)
    private val hdfc = Account("a1", "HDFC", "••4821", AccountKind.Bank, 0)

    private fun tx(id: String, kind: TxKind, paise: Long, cat: String?, date: String, time: String, merchant: String = "", source: TxSource = TxSource.User) =
        Transaction(id, kind, paise, cat, "a1", null, merchant, null, date, time, source)

    @Test fun todayShowsTimeAndSignedAmount() {
        val cats = listOf(food, salary)
        val accounts = listOf(hdfc)

        val dinner = txRowUi(tx("t1", TxKind.Expense, 45_000, "food", today, "13:05"), cats, accounts, today)
        assertEquals("Food & dining", dinner.title)
        assertEquals("Food & dining · Today · 1:05 PM", dinner.sub)
        assertEquals("−₹450", dinner.amount)
        assertFalse(dinner.positive)
        assertEquals("🍜", dinner.emoji)
        assertEquals("HDFC ••4821", dinner.accountLine)

        val pay = txRowUi(tx("t2", TxKind.Income, 10_000_000, "salary", "2026-10-01", "09:00", merchant = "Beyond Labs", source = TxSource.Recurring), cats, accounts, today)
        assertEquals("+₹1,00,000", pay.amount)
        assertTrue(pay.positive)
        assertEquals("Beyond Labs", pay.title)
        assertTrue(pay.recurring)

        val yesterday = txRowUi(tx("t3", TxKind.Expense, 12_000, "food", "2026-10-13", "20:30"), cats, accounts, today)
        assertEquals("Food & dining · Yesterday · 8:30 PM", yesterday.sub)

        val older = txRowUi(tx("t4", TxKind.Expense, 12_000, null, "2026-10-11", "20:30", source = TxSource.Ai), cats, accounts, today)
        assertEquals("Sun 11 Oct", older.sub)
        assertEquals("💸", older.emoji)
        assertTrue(older.ai)
    }
}
