package tech.beyondscale.mitra.core

import kotlin.math.abs
import kotlin.math.floor

/* Ports of inr / inrShort in mitra-frontend/src/lib/dates.ts — output must match the web exactly. */

/** JS Math.round: halves round toward +infinity. */
private fun jsRound(x: Double): Long = floor(x + 0.5).toLong()

private fun groupIndian(n: Long): String {
    val s = n.toString()
    if (s.length <= 3) return s
    val parts = ArrayDeque<String>()
    var rest = s.dropLast(3)
    while (rest.length > 2) { parts.addFirst(rest.takeLast(2)); rest = rest.dropLast(2) }
    if (rest.isNotEmpty()) parts.addFirst(rest)
    return parts.joinToString(",") + "," + s.takeLast(3)
}

/** JS Number.prototype.toFixed for the magnitudes we format. */
internal fun toFixed(x: Double, digits: Int): String {
    var factor = 1L
    repeat(digits) { factor *= 10 }
    val scaled = floor(abs(x) * factor + 0.5).toLong()
    val sign = if (x < 0 && scaled != 0L) "-" else ""
    val whole = scaled / factor
    if (digits == 0) return "$sign$whole"
    return "$sign$whole.${(scaled % factor).toString().padStart(digits, '0')}"
}

/** "₹12,34,567" — always positive, like the web. */
fun inr(rupees: Double): String = "₹" + groupIndian(abs(jsRound(rupees)))

/** "₹1.50L" · "₹2.5k" · "₹999" */
fun inrShort(rupees: Double): String = when {
    abs(rupees) >= 100_000 -> "₹${toFixed(rupees / 100_000, 2)}L"
    abs(rupees) >= 1_000 -> "₹${toFixed(rupees / 1_000, 1)}k"
    else -> inr(rupees)
}
