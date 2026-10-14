package tech.beyondscale.mitra.data.repository

import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.CategoryChange
import tech.beyondscale.mitra.domain.model.CategoryKind
import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyPatchesTest {
    @Test fun budgetClearSendsNull() {
        assertEquals("""{"budget":null}""", categoryPatch(listOf(CategoryChange.Budget(null))).toString())
        assertEquals("""{"name":"Food","budget":2000.0}""", categoryPatch(listOf(CategoryChange.Name("Food"), CategoryChange.Budget(200000))).toString())
        assertEquals("{}", categoryPatch(emptyList()).toString())
    }

    @Test fun categoryChangesApplyLocally() {
        val c = Category("c1", "Food", "🍔", CategoryKind.Expense, "food", 100000)
        assertEquals(c.copy(name = "Eating out", budgetPaise = null), c.applied(listOf(CategoryChange.Name("  Eating out "), CategoryChange.Budget(null))))
        assertEquals("""{"name":"Eating out"}""", categoryPatch(listOf(CategoryChange.Name("  Eating out "))).toString())
    }
}
