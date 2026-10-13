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

/**
 * round(a * f) half-up, computed on the exact binary value of [a] (a >= 0, f > 0, f <= 100).
 * a = m * 2^e exactly. For e < 0 the result is the largest n with (2n - 1) * 2^s <= 2*f*m, s = -e,
 * i.e. with q = floor(2*f*m / 2^s): n = floor((q + 1) / 2). Integer arithmetic only, no overflow:
 * 2*f*m < 2^61.
 */
private fun roundHalfUpExact(a: Double, f: Long): Long {
    val bits = a.toRawBits()
    val expBits = ((bits ushr 52) and 0x7FF).toInt()
    val frac = bits and ((1L shl 52) - 1)
    val m: Long
    val e: Int
    if (expBits == 0) {
        m = frac
        e = -1074
    } else {
        m = frac or (1L shl 52)
        e = expBits - 1075
    }
    if (m == 0L) return 0
    if (e >= 0) return (m shl e) * f // a is an integer
    val s = -e
    val t = 2 * f * m
    val q = if (s >= 63) 0L else t shr s
    return (q + 1) / 2
}

/** JS Number.prototype.toFixed for the magnitudes we format. */
internal fun toFixed(x: Double, digits: Int): String {
    var factor = 1L
    repeat(digits) { factor *= 10 }
    val scaled = roundHalfUpExact(abs(x), factor)
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
