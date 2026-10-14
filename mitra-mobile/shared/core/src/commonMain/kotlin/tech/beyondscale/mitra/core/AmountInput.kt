package tech.beyondscale.mitra.core

/** Server `max` for an amount: ₹1e9 in paise. */
const val MAX_AMOUNT_PAISE = 100_000_000_000L

/** Keypad / amount-field text rules (port of the web `Keypad` + `AmountField`). Text is a plain decimal string, at most 2 decimals. */
object AmountInput {
    private const val MAX_INT_DIGITS = 10

    fun press(current: String, key: Char): String = when {
        key == '⌫' -> backspace(current)
        key == '.' -> if ('.' in current) current else if (current.isEmpty()) "0." else "$current."
        key in '0'..'9' -> {
            val dot = current.indexOf('.')
            val candidate = if (current == "0") key.toString() else current + key
            val tooManyDecimals = dot >= 0 && current.length - dot > 2
            val intPart = if (dot >= 0) candidate.substring(0, dot) else candidate
            when {
                tooManyDecimals || intPart.length > MAX_INT_DIGITS -> current
                (toPaise(candidate) ?: 0L) > MAX_AMOUNT_PAISE -> current
                else -> candidate
            }
        }
        else -> current
    }

    fun backspace(current: String): String = current.dropLast(1)

    /** Keeps digits and the first `.` only (paste of `₹1,200.50` -> `1200.50`). */
    fun sanitize(typed: String): String {
        val sb = StringBuilder()
        var dot = false
        for (c in typed) {
            if (c in '0'..'9') sb.append(c)
            else if (c == '.' && !dot) { dot = true; sb.append(c) }
        }
        return sb.toString()
    }

    /** Exact decimal string -> paise (no Double); null for empty, `.`, zero or malformed input. */
    fun toPaise(text: String): Long? {
        val dot = text.indexOf('.')
        val intText = if (dot >= 0) text.substring(0, dot) else text
        val fracText = if (dot >= 0) text.substring(dot + 1) else ""
        if (intText.isEmpty() && fracText.isEmpty()) return null
        if (!intText.all { it in '0'..'9' } || !fracText.all { it in '0'..'9' } || fracText.length > 2) return null
        if (intText.length > 12) return null
        val rupees = if (intText.isEmpty()) 0L else intText.toLong()
        val paise = if (fracText.isEmpty()) 0L else fracText.padEnd(2, '0').toLong()
        val total = rupees * 100 + paise
        return if (total == 0L) null else total
    }

    /** Edit prefill, matching the web `String(amount)`: 123410 -> "1234.1", 120000 -> "1200". */
    fun fromPaise(paise: Long): String {
        val rupees = paise / 100
        val frac = (paise % 100).toInt()
        return when {
            frac == 0 -> "$rupees"
            frac % 10 == 0 -> "$rupees.${frac / 10}"
            else -> "$rupees.${frac.toString().padStart(2, '0')}"
        }
    }

    /** Live Indian grouping of the integer part, keeping the typed fraction; empty -> "0". */
    fun display(text: String): String {
        if (text.isEmpty()) return "0"
        val dot = text.indexOf('.')
        val intPart = (if (dot >= 0) text.substring(0, dot) else text).ifEmpty { "0" }
        val grouped = if (intPart.length <= 3) intPart else {
            val head = intPart.dropLast(3)
            val pairs = head.reversed().chunked(2).joinToString(",").reversed()
            "$pairs,${intPart.takeLast(3)}"
        }
        return if (dot >= 0) grouped + text.substring(dot) else grouped
    }
}
