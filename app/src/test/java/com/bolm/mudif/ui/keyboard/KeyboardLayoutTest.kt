package com.bolm.mudif.ui.keyboard

import android.view.KeyEvent
import com.bolm.mudif.ui.keyboard.KeyboardLayout.Action
import com.bolm.mudif.ui.keyboard.KeyboardLayout.Language
import com.bolm.mudif.ui.keyboard.KeyboardLayout.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * p118: the one unit test in the project. KeyboardLayout is pure
 * data (no views), so the rows are testable on the JVM: the
 * samples' letter order was illustrative (duplicated/misplaced
 * keys), so the test pins the real layouts down, no duplicate
 * label in a row, the right counts, Shift/Fn/symbols pages, and
 * the modifier keycode map. Runs with testDebugUnitTest.
 */
class KeyboardLayoutTest {

    private fun rows(
        language: Language,
        shift: ShiftState = ShiftState.OFF,
        fn: Boolean = false,
        symbolsOpen: Boolean = false
    ) = KeyboardLayout.rows(language, shift, fn, symbolsOpen)

    @Test
    fun `five rows in every state`() {
        for (language in Language.values()) {
            assertEquals(5, rows(language).size)
            assertEquals(5, rows(language, ShiftState.ONCE).size)
            assertEquals(5, rows(language, fn = true).size)
            assertEquals(5, rows(language, symbolsOpen = true).size)
        }
    }

    @Test
    fun `no duplicate label inside any row except the two shifts`() {
        // The samples' flaw (duplicated letters) must never ship:
        // every row is a set of distinct labels, except that a
        // real keyboard has TWO Shift keys (left + right), both
        // labelled "⇧": that pair is the only allowed duplicate.
        for (language in Language.values()) {
            for (shift in ShiftState.values()) {
                for (fn in listOf(false, true)) {
                    for (symbols in listOf(false, true)) {
                        for ((i, row) in rows(language, shift, fn, symbols).withIndex()) {
                            val grouped = row.groupBy { it.label }
                            for ((label, keys) in grouped) {
                                if (keys.size == 1) continue
                                assertEquals(
                                    "dup '$label' in $language/$shift/fn=$fn/symbols=$symbols row $i",
                                    "⇧", label
                                )
                                assertEquals(2, keys.size)
                                assertTrue(keys.all { it.action == Action.SHIFT })
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `arabic rows are the Gboard rows`() {
        val arabic = rows(Language.ARABIC)
        // Row 1 (physical left-to-right): د ج ح خ ه ع غ ف ق ث ص ض
        assertEquals(
            listOf("Tab", "د", "ج", "ح", "خ", "ه", "ع", "غ", "ف", "ق", "ث", "ص", "ض"),
            arabic[1].map { it.label }
        )
        // Row 2: ط ك م ن ت ا ل ب ي س ش
        assertEquals(
            listOf("Caps", "ط", "ك", "م", "ن", "ت", "ا", "ل", "ب", "ي", "س", "ش", "⏎"),
            arabic[2].map { it.label }
        )
        // Row 3: ظ ز و ة ى لا ر ؤ ء ئ
        assertEquals(
            listOf("⇧", "ظ", "ز", "و", "ة", "ى", "لا", "ر", "ؤ", "ء", "ئ", "⇧"),
            arabic[3].map { it.label }
        )
    }

    @Test
    fun `latin rows are computer QWERTY`() {
        val latin = rows(Language.LATIN)
        assertEquals(
            listOf("Tab", "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "[", "]"),
            latin[1].map { it.label }
        )
        assertEquals(
            listOf("Caps", "A", "S", "D", "F", "G", "H", "J", "K", "L", ";", "'", "⏎"),
            latin[2].map { it.label }
        )
    }

    @Test
    fun `arabic shift gives diacritics on the bottom row`() {
        val shifted = rows(Language.ARABIC, ShiftState.ONCE)
        // Logical reading right-to-left: َ ُ ِ ّ ً ٌ ٍ ْ ـ ،
        assertEquals(
            listOf("⇧", "،", "ـ", "ْ", "ٍ", "ٌ", "ً", "ّ", "ِ", "ُ", "َ", "⇧"),
            shifted[3].map { it.label }
        )
        // The digit row becomes the symbol row (mirrored).
        assertTrue(shifted[0].any { it.label == "!" })
        assertTrue(shifted[0].any { it.label == ")" })
    }

    @Test
    fun `fn gives F1 to F12 in both languages`() {
        for (language in Language.values()) {
            val fnRows = rows(language, fn = true)
            val fKeys = fnRows.flatten()
                .filter { it.action == Action.FUNCTION }
                .map { it.label }
            assertEquals(
                (1..12).map { "F$it" }, fKeys
            )
            val codes = fnRows.flatten()
                .filter { it.action == Action.FUNCTION }
                .mapNotNull { it.keyCode }
            assertEquals(
                (KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12).toList(), codes
            )
        }
    }

    @Test
    fun `symbols page has a return key to letters`() {
        val latinSymbols = rows(Language.LATIN, symbolsOpen = true)
        assertEquals("ABC", latinSymbols[2][0].label)
        assertEquals(Action.SYMBOLS, latinSymbols[2][0].action)
        val arabicSymbols = rows(Language.ARABIC, symbolsOpen = true)
        assertEquals("عربي", arabicSymbols[2][0].label)
        // The bottom row's 123 key becomes the current language's
        // letters label on the symbols page (tap to return).
        assertEquals("ABC", latinSymbols[4][5].label)
        assertEquals("عربي", arabicSymbols[4][5].label)
    }

    @Test
    fun `bottom row language key shows the other language`() {
        // On Latin, the key offers Arabic; on Arabic, it offers EN.
        assertEquals("عربي", rows(Language.LATIN)[4][3].label)
        assertEquals("EN", rows(Language.ARABIC)[4][3].label)
        // Space commits a real space, not the label "مسافة".
        assertEquals(" ", rows(Language.ARABIC)[4][4].commitText())
        assertEquals("مسافة", rows(Language.ARABIC)[4][4].label)
    }

    @Test
    fun `latin letters map to keycodes, arabic letters do not`() {
        assertEquals(KeyEvent.KEYCODE_A, KeyboardLayout.keyCodeForLatin("a"))
        assertEquals(KeyEvent.KEYCODE_A, KeyboardLayout.keyCodeForLatin("A"))
        assertEquals(KeyEvent.KEYCODE_Z, KeyboardLayout.keyCodeForLatin("z"))
        assertEquals(KeyEvent.KEYCODE_0, KeyboardLayout.keyCodeForLatin("0"))
        assertEquals(KeyEvent.KEYCODE_LEFT_BRACKET, KeyboardLayout.keyCodeForLatin("["))
        assertNull(KeyboardLayout.keyCodeForLatin("ض"))
        assertNull(KeyboardLayout.keyCodeForLatin("ا"))
        assertNull(KeyboardLayout.keyCodeForLatin(" "))
        assertNull(KeyboardLayout.keyCodeForLatin("لا"))
    }

    @Test
    fun `shifted latin is uppercase and shifted symbols`() {
        assertEquals("A", KeyboardLayout.shiftedLatin("a"))
        assertEquals("Z", KeyboardLayout.shiftedLatin("z"))
        assertEquals("!", KeyboardLayout.shiftedLatin("1"))
        assertEquals("?", KeyboardLayout.shiftedLatin("/"))
        assertEquals("{", KeyboardLayout.shiftedLatin("["))
        // Multi-char labels pass through unchanged.
        assertEquals("Esc", KeyboardLayout.shiftedLatin("Esc"))
    }

    @Test
    fun `latin commits lowercase unless shift is on`() {
        // Caught on the rig: the Latin test typed "HI" instead of
        // "hi" (the key labels are uppercase on screen, like
        // Gboard, but the committed text must follow the shift).
        val h = KeyboardLayout.Key("H")
        assertEquals("h", KeyboardLayout.commitTextFor(h, ShiftState.OFF))
        assertEquals("H", KeyboardLayout.commitTextFor(h, ShiftState.ONCE))
        assertEquals("H", KeyboardLayout.commitTextFor(h, ShiftState.LOCKED))
        // Arabic, digits and symbols are unaffected.
        assertEquals("ا", KeyboardLayout.commitTextFor(
            KeyboardLayout.Key("ا"), ShiftState.OFF))
        assertEquals("1", KeyboardLayout.commitTextFor(
            KeyboardLayout.Key("1"), ShiftState.OFF))
        assertEquals("!", KeyboardLayout.commitTextFor(
            KeyboardLayout.Key("!"), ShiftState.OFF))
        assertEquals(" ", KeyboardLayout.commitTextFor(
            KeyboardLayout.Key("مسافة", KeyboardLayout.Action.SPACE, text = " "),
            ShiftState.OFF))
    }
}
