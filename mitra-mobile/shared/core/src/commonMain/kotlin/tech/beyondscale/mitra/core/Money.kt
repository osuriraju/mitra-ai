package tech.beyondscale.mitra.core

import kotlin.math.abs
import kotlin.math.floor

/* Ports of inr / inrShort in mitra-frontend/src/lib/dates.ts — output must match the web exactly. */

/** JS Math.round: halves round toward +infinity. */
fun jsRound(x: Double): Long = floor(x + 0.5).toLong()

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

/* Paise (the device's integer money unit) through the same web formatters. */

/** "₹124" for 12350 paise — rounds to whole rupees like the web; never shows a sign. */
fun inrPaise(paise: Long): String = inr(paise / 100.0)

/** "₹1.50L" · "₹2.5k" · "₹999" for an amount in paise. */
fun inrShortPaise(paise: Long): String = inrShort(paise / 100.0)

/** "−₹120" (U+2212) when negative, "+₹120" otherwise — the web's signed net amounts. */
fun signedInrPaise(paise: Long): String = (if (paise < 0) "\u2212" else "+") + inrPaise(paise)

/* Goal numbers (plain doubles, not paise) — ports of `toLocaleString('en-IN')` and `String(n)`. */

/**
 * round(frac * f) half-up on the exact binary value of [frac] (0 <= frac < 1, f <= 1000), like [roundHalfUpExact]
 * but in unsigned arithmetic: 2 * f * m < 2^64 for m < 2^53.
 */
private fun roundFractionHalfUp(frac: Double, f: Long): Long {
    if (frac == 0.0) return 0
    val bits = frac.toRawBits()
    val expBits = ((bits ushr 52) and 0x7FF).toInt()
    val raw = bits and ((1L shl 52) - 1)
    val m = if (expBits == 0) raw else raw or (1L shl 52)
    val s = if (expBits == 0) 1074 else 1075 - expBits // frac < 1, so s > 0
    val t = 2uL * f.toULong() * m.toULong()
    val q = if (s >= 64) 0uL else t shr s
    return ((q + 1uL) / 2uL).toLong()
}

/**
 * `x.toLocaleString('en-IN')`: Indian digit grouping, at most 3 fraction digits (halves away from zero on the
 * exact value, like Intl's default `halfExpand`), trailing zeros dropped, minus sign kept.
 * `100000.0 → "1,00,000"`, `1234.5678 → "1,234.568"`, `0.5 → "0.5"`.
 */
fun localeIN(x: Double): String {
    if (x.isNaN()) return "NaN"
    val negative = x < 0.0 || (x == 0.0 && 1.0 / x < 0.0)
    if (x.isInfinite()) return if (negative) "-∞" else "∞"
    val a = abs(x)
    var whole = floor(a).toLong()
    var milli = roundFractionHalfUp(a - floor(a), 1000)
    if (milli == 1000L) { whole += 1; milli = 0 }
    val fraction = if (milli == 0L) "" else "." + milli.toString().padStart(3, '0').trimEnd('0')
    return (if (negative) "-" else "") + groupIndian(whole) + fraction
}

/** JavaScript `String(n)` for the magnitudes goals use: `58000.0 → "58000"`, `12.5 → "12.5"`, `-0.0 → "0"`. */
fun jsNum(x: Double): String {
    if (x.isNaN()) return "NaN"
    if (x.isInfinite()) return if (x > 0) "Infinity" else "-Infinity"
    if (x == 0.0) return "0"
    if (x == floor(x) && abs(x) < 1e18) return x.toLong().toString()
    val s = x.toString()
    val e = s.indexOfAny(charArrayOf('E', 'e'))
    if (e < 0) return s
    // Expand "1.25E-5" style output to the plain decimal JS prints for 1e-6 <= |x| < 1e21.
    val exp = s.substring(e + 1).toInt()
    val mantissa = s.substring(0, e)
    val sign = if (mantissa.startsWith("-")) "-" else ""
    val digits = mantissa.removePrefix("-").replace(".", "").trimEnd('0').ifEmpty { "0" }
    val point = mantissa.removePrefix("-").indexOf('.').let { if (it < 0) mantissa.removePrefix("-").length else it } + exp
    if (exp < -6 || exp >= 21) {
        val m = if (digits.length > 1) digits[0] + "." + digits.substring(1) else digits
        return "$sign${m}e${if (exp > 0) "+" else ""}$exp"
    }
    return sign + when {
        point <= 0 -> "0." + "0".repeat(-point) + digits
        point >= digits.length -> digits + "0".repeat(point - digits.length)
        else -> digits.substring(0, point) + "." + digits.substring(point)
    }
}
