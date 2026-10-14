package tech.beyondscale.mitra.domain.money

import tech.beyondscale.mitra.core.AmountInput
import tech.beyondscale.mitra.core.MAX_AMOUNT_PAISE
import tech.beyondscale.mitra.domain.model.Category
import tech.beyondscale.mitra.domain.model.CategoryKind
import tech.beyondscale.mitra.domain.tasks.ParsedField

data class ParsedExpense(val amountPaise: Long?, val category: Category?, val merchant: String)

/**
 * Spec §9 Quick Add parsing for expenses: an amount token (`₹240`, `240`, `₹1,200`, `240.50`, `rs 240`) at the start
 * or end, and the first word matching an expense category slug or a whole word of its name. The category word stays
 * in [ParsedExpense.merchant]. Fields in `ignore` are not parsed and their text stays in the merchant.
 */
object ExpenseParser {
    private val amountToken = Regex("""^(₹|rs\.?)?(\d{1,3}(,\d{2,3})*|\d+)(\.\d{1,2})?$""", RegexOption.IGNORE_CASE)
    private val prefixOnly = Regex("""^(₹|rs\.?)$""", RegexOption.IGNORE_CASE)
    private val prefix = Regex("""^(₹|rs\.?)""", RegexOption.IGNORE_CASE)
    private val nonLetters = Regex("[^\\p{L}]+")

    fun parse(input: String, categories: List<Category>, ignore: Set<ParsedField> = emptySet()): ParsedExpense {
        var words = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var amount: Long? = null

        if (ParsedField.Amount !in ignore) {
            val lead = leadingAmount(words)
            if (lead != null) {
                amount = lead.first; words = words.drop(lead.second)
            } else {
                val trail = trailingAmount(words)
                if (trail != null) { amount = trail.first; words = words.dropLast(trail.second) }
            }
        }

        var category: Category? = null
        if (ParsedField.Category !in ignore) {
            val expense = categories.filter { it.kind == CategoryKind.Expense }
            category = words.firstNotNullOfOrNull { w ->
                val word = w.trim { !it.isLetter() }.lowercase()
                if (word.length < 3) null
                else expense.firstOrNull { c ->
                    c.slug?.lowercase() == word ||
                        c.name.lowercase().split(nonLetters).any { it.length >= 3 && it == word }
                }
            }
        }
        return ParsedExpense(amount, category, words.joinToString(" "))
    }

    private fun value(token: String): Long? =
        if (amountToken.matches(token)) {
            AmountInput.toPaise(token.replace(prefix, "").replace(",", ""))?.takeIf { it <= MAX_AMOUNT_PAISE }
        } else null

    private fun leadingAmount(w: List<String>): Pair<Long, Int>? {
        if (w.size >= 2 && prefixOnly.matches(w[0])) value(w[1])?.let { return it to 2 }
        return w.firstOrNull()?.let { t -> value(t)?.let { it to 1 } }
    }

    private fun trailingAmount(w: List<String>): Pair<Long, Int>? {
        if (w.size >= 2 && prefixOnly.matches(w[w.size - 2])) value(w.last())?.let { return it to 2 }
        return w.lastOrNull()?.let { t -> value(t)?.let { it to 1 } }
    }
}
