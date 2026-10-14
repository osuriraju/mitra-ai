package tech.beyondscale.mitra.domain.money

import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.CategoryKind
import tech.beyondscale.mitra.domain.tasks.ParsedField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExpenseParserTest {
    private val cats = listOf(
        Category("c1", "Food & dining", "🍔", CategoryKind.Expense, slug = "food"),
        Category("c2", "Groceries", "🛒", CategoryKind.Expense, slug = "groceries"),
        Category("c3", "Salary", "💰", CategoryKind.Income, slug = "salary"),
    )

    @Test
    fun leadingRupeeAmountAndCategory() {
        val p = ExpenseParser.parse("₹240 food lunch", cats)
        assertEquals(24000L, p.amountPaise)
        assertEquals("food", p.category?.slug)
        assertEquals("food lunch", p.merchant)
    }

    @Test
    fun trailingPlainAmount() {
        val p = ExpenseParser.parse("uber to office 180", cats)
        assertEquals(18000L, p.amountPaise)
        assertNull(p.category)
        assertEquals("uber to office", p.merchant)
    }

    @Test
    fun groupedAndDecimalAmount() {
        val p = ExpenseParser.parse("₹1,200.50 groceries dmart", cats)
        assertEquals(120050L, p.amountPaise)
        assertEquals("groceries", p.category?.slug)
        assertEquals("groceries dmart", p.merchant)
    }

    @Test
    fun rsPrefixAndNameWord() {
        assertEquals(24000L, ExpenseParser.parse("rs 240 coffee", cats).amountPaise)
        assertEquals(24000L, ExpenseParser.parse("Rs. 240 coffee", cats).amountPaise)
        assertEquals("food", ExpenseParser.parse("dinner Dining 90", cats).category?.slug)
    }

    @Test
    fun amountInTheMiddleIsNotParsed() {
        assertNull(ExpenseParser.parse("paid 240 to ravi", cats).amountPaise)
    }

    @Test
    fun ignoredCategoryFallsBackToText() {
        val p = ExpenseParser.parse("240 food", cats, ignore = setOf(ParsedField.Category))
        assertNull(p.category)
        assertEquals("food", p.merchant)
        assertEquals(24000L, p.amountPaise)
    }

    @Test
    fun ignoredAmountFallsBackToText() {
        val p = ExpenseParser.parse("240 food", cats, ignore = setOf(ParsedField.Amount))
        assertNull(p.amountPaise)
        assertEquals("240 food", p.merchant)
    }

    @Test
    fun incomeCategoriesNeverMatch() {
        assertNull(ExpenseParser.parse("500 salary", cats).category)
    }
}
