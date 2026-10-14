package tech.beyondscale.mitra.feature.notes

import kotlin.test.Test
import kotlin.test.assertEquals

class NoteFormatTest {
    @Test fun linePrefixesToggleAndReplaceOnTheCursorLine() {
        val text = "one\ntwo"
        assertEquals(FormatEdit("one\n# two", 7, 7), applyFormat(FormatEdit(text, 5, 5), NoteFormat.H1))
        assertEquals(FormatEdit("one\n## two", 8, 8), applyFormat(FormatEdit("one\n# two", 7, 7), NoteFormat.H2))
        assertEquals(FormatEdit("one\ntwo", 5, 5), applyFormat(FormatEdit("one\n## two", 8, 8), NoteFormat.H2))
        assertEquals(FormatEdit("- [ ] one\ntwo", 7, 7), applyFormat(FormatEdit(text, 1, 1), NoteFormat.Check))
        // A ticked item toggles off with the checklist button too.
        assertEquals(FormatEdit("one", 0, 0), applyFormat(FormatEdit("- [x] one", 3, 3), NoteFormat.Check))
        assertEquals(FormatEdit("- ", 2, 2), applyFormat(FormatEdit("", 0, 0), NoteFormat.Bullet))
    }

    @Test fun linkWrapsSelectionOrInsertsEmptyBrackets() {
        assertEquals(FormatEdit("see [[Plan]] now", 12, 12), applyFormat(FormatEdit("see Plan now", 4, 8), NoteFormat.Link))
        assertEquals(FormatEdit("x[[]]", 3, 3), applyFormat(FormatEdit("x", 1, 1), NoteFormat.Link))
    }
}
