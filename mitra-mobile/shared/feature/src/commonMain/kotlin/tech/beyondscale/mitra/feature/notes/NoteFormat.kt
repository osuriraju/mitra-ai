package tech.beyondscale.mitra.feature.notes

/** The editor's formatting toolbar (web toolbar: H1, H2, •, ☐, 🔗). */
enum class NoteFormat(val label: String, val prefix: String) {
    H1("H1", "# "), H2("H2", "## "), Bullet("•", "- "), Check("☐", "- [ ] "), Link("[[ ]]", "[["),
}

/** Text plus a selection, as the editor field holds it. */
data class FormatEdit(val text: String, val selStart: Int, val selEnd: Int)

private val LINE_PREFIXES = listOf("- [ ] ", "- [x] ", "## ", "# ", "- ")

/**
 * Applies [f] at the cursor. Line formats toggle the prefix of the line the cursor is on (replacing another heading /
 * list prefix); [NoteFormat.Link] wraps the selection in `[[ ]]`, or inserts `[[]]` with the cursor inside.
 */
fun applyFormat(edit: FormatEdit, f: NoteFormat): FormatEdit {
    val text = edit.text
    val start = edit.selStart.coerceIn(0, text.length)
    val end = edit.selEnd.coerceIn(start, text.length)
    if (f == NoteFormat.Link) {
        val inner = text.substring(start, end)
        val out = text.substring(0, start) + "[[" + inner + "]]" + text.substring(end)
        return if (inner.isEmpty()) FormatEdit(out, start + 2, start + 2) else FormatEdit(out, start + 4 + inner.length, start + 4 + inner.length)
    }
    val lineStart = text.lastIndexOf('\n', start - 1).let { if (it < 0) 0 else it + 1 }
    val line = text.substring(lineStart)
    val existing = LINE_PREFIXES.firstOrNull { line.startsWith(it) }
    val (removed, added) = when {
        existing == f.prefix || (f == NoteFormat.Check && existing == "- [x] ") -> existing.length to ""
        existing != null -> existing.length to f.prefix
        else -> 0 to f.prefix
    }
    val out = text.substring(0, lineStart) + added + text.substring(lineStart + removed)
    val shift = added.length - removed
    fun move(i: Int) = if (i < lineStart + removed) maxOf(lineStart + added.length, i + shift).coerceAtMost(out.length) else i + shift
    return FormatEdit(out, move(start), move(end))
}
