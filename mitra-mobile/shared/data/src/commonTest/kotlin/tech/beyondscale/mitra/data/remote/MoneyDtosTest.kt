package tech.beyondscale.mitra.data.remote

import kotlinx.serialization.json.Json
import tech.beyondscale.mitra.domain.model.Transaction
import tech.beyondscale.mitra.domain.model.TxKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyDtosTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = true }

    private fun tx(amount: Double) = TransactionDto(id = "t1", kind = "expense", amount = amount, accountId = "a1", date = "2026-10-14")

    @Test fun paiseRoundTripsThroughRupees() {
        assertEquals(435L, tx(4.35).toDomain().amountPaise)
        assertEquals(123410L, tx(1234.1).toDomain().amountPaise)
        assertEquals(7L, tx(0.07).toDomain().amountPaise)

        val body = Transaction("t1", TxKind.Expense, 435, null, "a1", null, "Chai", null, "2026-10-14", "09:30").createBody()
        assertTrue("\"amount\":4.35" in body.toString(), body.toString())

        val cat = json.decodeFromString(CategoryDto.serializer(), """{"id":"c1","name":"Food","emoji":"🍔","kind":"expense","budget":null,"archived":false}""")
        assertNull(cat.toDomain().budgetPaise)
        assertEquals(200000L, json.decodeFromString(CategoryDto.serializer(), """{"id":"c1","name":"Food","emoji":"🍔","kind":"expense","budget":2000}""").toDomain().budgetPaise)

        val sparse = json.decodeFromString(
            TransactionDto.serializer(),
            """{"id":"t2","kind":"income","amount":1500.5,"accountId":"a1","merchant":"Salary","date":"2026-10-01","time":"10:00","source":"user","tags":[]}""",
        ).toDomain()
        assertNull(sparse.categoryId)
        assertNull(sparse.note)
        assertNull(sparse.recurringId)
        assertEquals(150050L, sparse.amountPaise)
        assertEquals(TxKind.Income, sparse.kind)
        val rec = json.decodeFromString(
            RecurringDto.serializer(),
            """{"id":"r1","name":"Rent","emoji":"🏠","amount":15000,"day":5,"method":"UPI","kind":"bill","reminder":true}""",
        ).toDomain()
        assertNull(rec.lastPaidOn)
        assertEquals(1500000L, rec.amountPaise)
    }

    @Test fun emptyCategoryIdBecomesNull() {
        assertNull(tx(10.0).copy(categoryId = "").toDomain().categoryId)
        val body = Transaction("t1", TxKind.Expense, 1000, "", "a1", "a2", "", " ", "2026-10-14", "09:30").createBody().toString()
        assertTrue("\"categoryId\":null" in body, body)
        assertTrue("\"toAccountId\":null" in body, body)
        assertTrue("\"note\":null" in body, body)
    }
}
