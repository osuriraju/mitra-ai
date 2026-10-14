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
 * The shortest round-trip decimal of |x| (finite, non-zero) as significant digits plus the decimal point's position:
 * 1.0005 → ("10005", 1), 5.0E-4 → ("5", -3), 1.0E7 → ("1", 8).
 *
 * Built on `Double.toString`, which on older JVMs is not always the shortest representation for some values near
 * 1e23 (JDK-4511638); goal numbers are capped at 1e12 by the server, far below that.
 */
private fun shortestDigits(a: Double): Pair<String, Int> {
    val s = abs(a).toString()
    val e = s.indexOfAny(charArrayOf('E', 'e'))
    val mantissa = if (e < 0) s else s.substring(0, e)
    val exp = if (e < 0) 0 else s.substring(e + 1).toInt()
    val dot = mantissa.indexOf('.').let { if (it < 0) mantissa.length else it }
    var digits = mantissa.replace(".", "")
    var point = dot + exp
    val lead = digits.length - digits.trimStart('0').length
    digits = digits.substring(lead).trimEnd('0').ifEmpty { "0" }
    point -= lead
    return digits to point
}

/** Plain-decimal integer and fraction digit strings of |x| from [shortestDigits]: 1.0005 → ("1", "0005"). */
private fun plainParts(a: Double): Pair<String, String> {
    val (digits, point) = shortestDigits(a)
    return when {
        point <= 0 -> "0" to "0".repeat(-point) + digits
        point >= digits.length -> digits + "0".repeat(point - digits.length) to ""
        else -> digits.substring(0, point) to digits.substring(point)
    }
}

/**
 * `x.toLocaleString('en-IN')`: Indian digit grouping, at most 3 fraction digits, trailing zeros dropped, minus sign
 * kept. Like V8/ICU it rounds the shortest round-trip decimal half-expand (1.0005 → "1.001", 9.9995 → "10"), not the
 * exact binary value. `100000.0 → "1,00,000"`, `1234.5678 → "1,234.568"`, `0.5 → "0.5"`.
 */
fun localeIN(x: Double): String {
    if (x.isNaN()) return "NaN"
    val negative = x < 0.0 || (x == 0.0 && 1.0 / x < 0.0)
    if (x.isInfinite()) return if (negative) "-∞" else "∞"
    val sign = if (negative) "-" else ""
    if (x == 0.0) return sign + "0"
    val (intPart, frac) = plainParts(x)
    var whole = intPart.toLong()
    var milli = frac.take(3).padEnd(3, '0').toInt()
    if (frac.length > 3 && frac[3] >= '5') milli += 1
    if (milli == 1000) { whole += 1; milli = 0 }
    val fraction = if (milli == 0) "" else "." + milli.toString().padStart(3, '0').trimEnd('0')
    return sign + groupIndian(whole) + fraction
}

/**
 * JavaScript `String(n)` for the magnitudes goals use: `58000.0 → "58000"`, `12.5 → "12.5"`, `-0.0 → "0"`.
 * Relies on `Double.toString` being the shortest round-trip decimal, which older JVMs miss for a few values near
 * 1e23 (see [shortestDigits]); goal numbers are capped at 1e12 by the server.
 */
fun jsNum(x: Double): String {
    if (x.isNaN()) return "NaN"
    if (x.isInfinite()) return if (x > 0) "Infinity" else "-Infinity"
    if (x == 0.0) return "0"
    val sign = if (x < 0) "-" else ""
    val (digits, point) = shortestDigits(x)
    val exp = point - 1
    if (exp < -6 || exp >= 21) {
        val m = if (digits.length > 1) digits[0] + "." + digits.substring(1) else digits
        return "$sign${m}e${if (exp > 0) "+" else ""}$exp"
    }
    val (intPart, frac) = plainParts(x)
    return sign + intPart + (if (frac.isEmpty()) "" else ".$frac")
}
