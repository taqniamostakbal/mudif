package com.bolm.mudif.ui.keyboard

import android.view.KeyEvent

/**
 * p118: layout data for the Mudif keyboard. Pure data, no Android
 * views here, so the rows can be unit-tested (see KeyboardLayoutTest).
 *
 * The samples' letter order is ILLUSTRATIVE (AI-generated, some
 * keys duplicated/misplaced) and is NOT copied. The real layouts
 * are the standard ones the user already types on:
 * Latin = computer QWERTY; Arabic = the Gboard Arabic rows.
 *
 * Rows are stored in PHYSICAL left-to-right order (the order the
 * keys appear on screen), so both languages keep the same key
 * positions and muscle memory survives a language switch. The
 * Arabic rows read right-to-left; the logical reading is noted
 * per row.
 */
object KeyboardLayout {

    enum class Action {
        TEXT, BACKSPACE, ENTER, TAB, ESC, SHIFT, CAPS, CTRL, ALT,
        FN, LANG, SYMBOLS, SPACE, ARROW, FUNCTION
    }

    /** Styling: LETTER = plain key; ACCENT = purple action key. */
    enum class Kind { LETTER, ACTION, ACCENT }

    data class Key(
        val label: String,
        val action: Action = Action.TEXT,
        /** Text committed for TEXT keys; defaults to [label]. */
        val text: String? = null,
        /** Android keycode for special keys (ENTER, TAB, ESC, arrows,
         *  F-keys). Null for plain text keys. */
        val keyCode: Int? = null,
        /** Width weight inside its row. */
        val weight: Float = 1f,
        val kind: Kind = Kind.LETTER
    ) {
        fun commitText(): String = text ?: label
    }

    enum class Language { LATIN, ARABIC }

    /** Shift state: OFF, ONCE (next key only), LOCKED (caps lock). */
    enum class ShiftState { OFF, ONCE, LOCKED }

    // -- Latin (computer QWERTY) ------------------------------------

    private val latinRow0 = listOf(
        Key("Esc", Action.ESC, keyCode = KeyEvent.KEYCODE_ESCAPE, kind = Kind.ACCENT),
        Key("1"), Key("2"), Key("3"), Key("4"), Key("5"),
        Key("6"), Key("7"), Key("8"), Key("9"), Key("0"),
        Key("⌫", Action.BACKSPACE, kind = Kind.ACCENT, weight = 1.5f)
    )
    private val latinRow0Shift = listOf(
        Key("Esc", Action.ESC, keyCode = KeyEvent.KEYCODE_ESCAPE, kind = Kind.ACCENT),
        Key("!"), Key("@"), Key("#"), Key("$"), Key("%"),
        Key("^"), Key("&"), Key("*"), Key("("), Key(")"),
        Key("⌫", Action.BACKSPACE, kind = Kind.ACCENT, weight = 1.5f)
    )
    private val latinRow0Fn = listOf(
        Key("Esc", Action.ESC, keyCode = KeyEvent.KEYCODE_ESCAPE, kind = Kind.ACCENT),
        Key("F1", Action.FUNCTION, keyCode = KeyEvent.KEYCODE_F1),
        Key("F2", Action.FUNCTION, keyCode = KeyEvent.KEYCODE_F2),
        Key("F3", Action.FUNCTION, keyCode = KeyEvent.KEYCODE_F3),
        Key("F4", Action.FUNCTION, keyCode = KeyEvent.KEYCODE_F4),
        Key("F5", Action.FUNCTION, keyCode = KeyEvent.KEYCODE_F5),
        Key("F6", Action.FUNCTION, keyCode = KeyEvent.KEYCODE_F6),
        Key("F7", Action.FUNCTION, keyCode = KeyEvent.KEYCODE_F7),
        Key("F8", Action.FUNCTION, keyCode = KeyEvent.KEYCODE_F8),
        Key("F9", Action.FUNCTION, keyCode = KeyEvent.KEYCODE_F9),
        Key("F10", Action.FUNCTION, keyCode = KeyEvent.KEYCODE_F10),
        Key("⌫", Action.BACKSPACE, kind = Kind.ACCENT, weight = 1.5f)
    )
    // F11/F12 live on the Fn number row too: the row above has 10
    // digit slots; F11/F12 replace the two Shift slots of row 3
    // when Fn is on (see fnRow3 below). No slot is wasted.
    private val latinRow1 = listOf(
        Key("Tab", Action.TAB, keyCode = KeyEvent.KEYCODE_TAB, kind = Kind.ACCENT, weight = 1.5f),
        Key("Q"), Key("W"), Key("E"), Key("R"), Key("T"), Key("Y"),
        Key("U"), Key("I"), Key("O"), Key("P"), Key("["), Key("]")
    )
    private val latinRow2 = listOf(
        Key("Caps", Action.CAPS, kind = Kind.ACCENT, weight = 1.5f),
        Key("A"), Key("S"), Key("D"), Key("F"), Key("G"), Key("H"),
        Key("J"), Key("K"), Key("L"), Key(";"), Key("'"),
        Key("⏎", Action.ENTER, keyCode = KeyEvent.KEYCODE_ENTER, kind = Kind.ACCENT, weight = 1.5f)
    )
    private val latinRow3 = listOf(
        Key("⇧", Action.SHIFT, kind = Kind.ACCENT, weight = 1.5f),
        Key("Z"), Key("X"), Key("C"), Key("V"), Key("B"), Key("N"),
        Key("M"), Key(","), Key("."), Key("/"),
        Key("⇧", Action.SHIFT, kind = Kind.ACCENT, weight = 1.5f)
    )
    // p119: Latin Shift changes rows 1-3 too, not just the digit row.
    // Before p119 only row 0 had a shifted variant, so Shift+"." committed
    // "." (rig: `cat .` instead of `cat >`) and the shifted row 3 still
    // DISPLAYED ", . /". These are the shiftedLatin() mapping applied
    // literally to each row: [ ] -> { }, ; ' -> : ", , . / -> < > ?.
    // Letters keep their uppercase labels; commitTextFor types them
    // uppercase while Shift is on, lowercase when it is off.
    private val latinRow1Shift = listOf(
        Key("Tab", Action.TAB, keyCode = KeyEvent.KEYCODE_TAB, kind = Kind.ACCENT, weight = 1.5f),
        Key("Q"), Key("W"), Key("E"), Key("R"), Key("T"), Key("Y"),
        Key("U"), Key("I"), Key("O"), Key("P"), Key("{"), Key("}")
    )
    private val latinRow2Shift = listOf(
        Key("Caps", Action.CAPS, kind = Kind.ACCENT, weight = 1.5f),
        Key("A"), Key("S"), Key("D"), Key("F"), Key("G"), Key("H"),
        Key("J"), Key("K"), Key("L"), Key(":"), Key("\""),
        Key("⏎", Action.ENTER, keyCode = KeyEvent.KEYCODE_ENTER, kind = Kind.ACCENT, weight = 1.5f)
    )
    private val latinRow3Shift = listOf(
        Key("⇧", Action.SHIFT, kind = Kind.ACCENT, weight = 1.5f),
        Key("Z"), Key("X"), Key("C"), Key("V"), Key("B"), Key("N"),
        Key("M"), Key("<"), Key(">"), Key("?"),
        Key("⇧", Action.SHIFT, kind = Kind.ACCENT, weight = 1.5f)
    )
    private val latinRow3Fn = listOf(
        Key("F11", Action.FUNCTION, keyCode = KeyEvent.KEYCODE_F11, kind = Kind.ACCENT, weight = 1.5f),
        Key("Z"), Key("X"), Key("C"), Key("V"), Key("B"), Key("N"),
        Key("M"), Key(","), Key("."), Key("/"),
        Key("F12", Action.FUNCTION, keyCode = KeyEvent.KEYCODE_F12, kind = Kind.ACCENT, weight = 1.5f)
    )

    // -- Arabic (Gboard rows, physical left-to-right) ---------------
    // Logical reading (right-to-left): row 1 = ض ص ث ق ف غ ع ه خ ح
    // ج د; row 2 = ش س ي ب ل ا ت ن م ك ط; row 3 = ئ ء ؤ ر لا ى ة و
    // ز ظ. The physical order below is the mirror of that, so Caps
    // stays left and Enter stays right, exactly like Latin.

    private val arabicRow0 = listOf(
        Key("Esc", Action.ESC, keyCode = KeyEvent.KEYCODE_ESCAPE, kind = Kind.ACCENT),
        Key("٠"), Key("٩"), Key("٨"), Key("٧"), Key("٦"),
        Key("٥"), Key("٤"), Key("٣"), Key("٢"), Key("١"),
        Key("⌫", Action.BACKSPACE, kind = Kind.ACCENT, weight = 1.5f)
    )
    private val arabicRow0Shift = listOf(
        Key("Esc", Action.ESC, keyCode = KeyEvent.KEYCODE_ESCAPE, kind = Kind.ACCENT),
        Key(")"), Key("("), Key("*"), Key("&"), Key("^"),
        Key("%"), Key("$"), Key("#"), Key("@"), Key("!"),
        Key("⌫", Action.BACKSPACE, kind = Kind.ACCENT, weight = 1.5f)
    )
    private val arabicRow1 = listOf(
        Key("Tab", Action.TAB, keyCode = KeyEvent.KEYCODE_TAB, kind = Kind.ACCENT, weight = 1.5f),
        Key("د"), Key("ج"), Key("ح"), Key("خ"), Key("ه"), Key("ع"),
        Key("غ"), Key("ف"), Key("ق"), Key("ث"), Key("ص"), Key("ض")
    )
    private val arabicRow2 = listOf(
        Key("Caps", Action.CAPS, kind = Kind.ACCENT, weight = 1.5f),
        Key("ط"), Key("ك"), Key("م"), Key("ن"), Key("ت"), Key("ا"),
        Key("ل"), Key("ب"), Key("ي"), Key("س"), Key("ش"),
        Key("⏎", Action.ENTER, keyCode = KeyEvent.KEYCODE_ENTER, kind = Kind.ACCENT, weight = 1.5f)
    )
    private val arabicRow3 = listOf(
        Key("⇧", Action.SHIFT, kind = Kind.ACCENT, weight = 1.5f),
        Key("ظ"), Key("ز"), Key("و"), Key("ة"), Key("ى"), Key("لا"),
        Key("ر"), Key("ؤ"), Key("ء"), Key("ئ"),
        Key("⇧", Action.SHIFT, kind = Kind.ACCENT, weight = 1.5f)
    )
    // Arabic Shift changes only the bottom letter row (Arabic has
    // no case): diacritics + tatweel + comma. Logical reading
    // right-to-left: َ ُ ِ ّ ً ٌ ٍ ْ ـ ،
    private val arabicRow3Shift = listOf(
        Key("⇧", Action.SHIFT, kind = Kind.ACCENT, weight = 1.5f),
        Key("،"), Key("ـ"), Key("ْ"), Key("ٍ"), Key("ٌ"), Key("ً"),
        Key("ّ"), Key("ِ"), Key("ُ"), Key("َ"),
        Key("⇧", Action.SHIFT, kind = Kind.ACCENT, weight = 1.5f)
    )
    // Arabic Fn: the same letters as arabicRow3, but the two Shift
    // slots become F11/F12 (exactly like Latin's latinRow3Fn), so
    // F11/F12 are reachable in Arabic too instead of only in Latin.
    private val arabicRow3Fn = listOf(
        Key("F11", Action.FUNCTION, keyCode = KeyEvent.KEYCODE_F11, kind = Kind.ACCENT, weight = 1.5f),
        Key("ظ"), Key("ز"), Key("و"), Key("ة"), Key("ى"), Key("لا"),
        Key("ر"), Key("ؤ"), Key("ء"), Key("ئ"),
        Key("F12", Action.FUNCTION, keyCode = KeyEvent.KEYCODE_F12, kind = Kind.ACCENT, weight = 1.5f)
    )

    // -- Bottom row (both languages, same positions) ----------------

    private fun bottomRow(language: Language, symbolsOpen: Boolean): List<Key> {
        // The language key shows the OTHER language (tap to switch).
        val langLabel = if (language == Language.LATIN) "عربي" else "EN"
        // The 123 key opens the symbols page; on it, it returns to
        // letters, so it shows the CURRENT language's letters label
        // ("ABC" / "عربي", the same label as the symbols page's
        // return key on row 2). It must NOT show langLabel: on the
        // Latin symbols page that would duplicate the LANG key
        // ("عربي" twice, two different actions), caught by
        // KeyboardLayoutTest.
        val symLabel = when {
            symbolsOpen && language == Language.LATIN -> "ABC"
            symbolsOpen -> "عربي"
            else -> "123"
        }
        return listOf(
            Key("Ctrl", Action.CTRL, kind = Kind.ACCENT),
            Key("Fn", Action.FN, kind = Kind.ACCENT),
            Key("Alt", Action.ALT, kind = Kind.ACCENT),
            Key(langLabel, Action.LANG, kind = Kind.ACCENT),
            Key("مسافة", Action.SPACE, text = " ", kind = Kind.ACCENT, weight = 4f),
            Key(symLabel, Action.SYMBOLS, kind = Kind.ACCENT),
            Key("←", Action.ARROW, keyCode = KeyEvent.KEYCODE_DPAD_LEFT),
            Key("↑", Action.ARROW, keyCode = KeyEvent.KEYCODE_DPAD_UP),
            Key("↓", Action.ARROW, keyCode = KeyEvent.KEYCODE_DPAD_DOWN),
            Key("→", Action.ARROW, keyCode = KeyEvent.KEYCODE_DPAD_RIGHT)
        )
    }

    // -- Symbols page (both languages) ------------------------------
    // Every key is a real symbol; there is no Shift here (nothing
    // to shift, all symbols are directly on the keys).

    private val symbolsRow1 = listOf(
        Key("Tab", Action.TAB, keyCode = KeyEvent.KEYCODE_TAB, kind = Kind.ACCENT, weight = 1.5f),
        Key("!"), Key("@"), Key("#"), Key("$"), Key("%"),
        Key("^"), Key("&"), Key("*"), Key("("), Key(")"), Key("-"), Key("_")
    )
    private val symbolsRow2 = listOf(
        // Return key: label = the language it returns to.
        Key("="), Key("+"), Key("["), Key("]"), Key("{"),
        Key("}"), Key(";"), Key(":"), Key("\""), Key("'"), Key(",")
    )
    private val symbolsRow3 = listOf(
        Key("<"), Key(">"), Key("\\"), Key("|"), Key("`"), Key("~"),
        Key("€"), Key("£"), Key("¥"), Key("¢"), Key("±"), Key("×")
    )

    /**
     * Rows for the current state. [fn] replaces the number row with
     * F1..F10 (+F11/F12 on row 3) and is independent of [shift].
     * [symbolsOpen] replaces rows 1-3 with the symbols page (row 0
     * stays digits, row 4 stays the bottom row).
     */
    fun rows(
        language: Language,
        shift: ShiftState,
        fn: Boolean,
        symbolsOpen: Boolean
    ): List<List<Key>> {
        val shifted = shift != ShiftState.OFF
        // Fn replaces the number row with F1..F10 (F11/F12 take the
        // row-3 Shift slots); F-keys are language-independent, so
        // the Fn row is the same in both languages.
        val row0Final = if (fn) {
            latinRow0Fn
        } else when {
            shifted && language == Language.LATIN -> latinRow0Shift
            shifted -> arabicRow0Shift
            language == Language.LATIN -> latinRow0
            else -> arabicRow0
        }
        if (symbolsOpen) {
            val returnLabel = if (language == Language.LATIN) "ABC" else "عربي"
            val row2 = listOf(
                Key(returnLabel, Action.SYMBOLS, kind = Kind.ACCENT, weight = 1.5f)
            ) + symbolsRow2 + listOf(
                Key("⏎", Action.ENTER, keyCode = KeyEvent.KEYCODE_ENTER, kind = Kind.ACCENT, weight = 1.5f)
            )
            return listOf(
                row0Final,
                symbolsRow1,
                row2,
                symbolsRow3,
                bottomRow(language, symbolsOpen = true)
            )
        }
        val row1 = when {
            shifted && language == Language.LATIN -> latinRow1Shift
            language == Language.LATIN -> latinRow1
            else -> arabicRow1
        }
        val row2 = when {
            shifted && language == Language.LATIN -> latinRow2Shift
            language == Language.LATIN -> latinRow2
            else -> arabicRow2
        }
        val row3 = when {
            fn && language == Language.LATIN -> latinRow3Fn
            fn -> arabicRow3Fn // Fn in Arabic: F11/F12 replace Shift
            shifted && language == Language.ARABIC -> arabicRow3Shift
            shifted && language == Language.LATIN -> latinRow3Shift
            else -> if (language == Language.LATIN) latinRow3 else arabicRow3
        }
        return listOf(row0Final, row1, row2, row3, bottomRow(language, symbolsOpen = false))
    }

    /** The text a TEXT key commits, given the shift state. Latin
     *  letters are lowercase unless Shift is one-shot or locked:
     *  the key LABELS are uppercase on screen (like Gboard, and
     *  like the samples), but the committed text follows the shift
     *  state: an unshifted Latin keyboard types lowercase. Arabic
     *  letters, digits and symbols are unaffected (Arabic has no
     *  case; the shifted digit row already carries its own keys).
     *  Caught on the rig: the Latin test typed "HI" instead of
     *  "hi" before this existed. */
    fun commitTextFor(key: Key, shift: ShiftState): String {
        val text = key.commitText()
        if (text.length == 1 && text[0] in 'A'..'Z' && shift == ShiftState.OFF) {
            return text.lowercase()
        }
        return text
    }

    /** Latin letter -> Android keycode, for sending a letter with a
     *  modifier held (Ctrl/Alt). Null = no keycode (Arabic letters
     *  and symbols without one); the service swallows those keys
     *  while a modifier is held instead of guessing. */
    fun keyCodeForLatin(text: String): Int? {
        if (text.length != 1) return null
        val c = text[0]
        return when {
            c in 'a'..'z' -> KeyEvent.KEYCODE_A + (c - 'a')
            c in 'A'..'Z' -> KeyEvent.KEYCODE_A + (c - 'A')
            c in '0'..'9' -> KeyEvent.KEYCODE_0 + (c - '0')
            c == '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
            c == ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
            c == ';' -> KeyEvent.KEYCODE_SEMICOLON
            c == '\'' -> KeyEvent.KEYCODE_APOSTROPHE
            c == ',' -> KeyEvent.KEYCODE_COMMA
            c == '.' -> KeyEvent.KEYCODE_PERIOD
            c == '/' -> KeyEvent.KEYCODE_SLASH
            c == '-' -> KeyEvent.KEYCODE_MINUS
            c == '=' -> KeyEvent.KEYCODE_EQUALS
            c == '\\' -> KeyEvent.KEYCODE_BACKSLASH
            c == '`' -> KeyEvent.KEYCODE_GRAVE
            else -> null
        }
    }

    /** The shifted form of a Latin letter/symbol key (Shift held).
     *  Arabic letters have no shifted form (see arabicRow3Shift). */
    fun shiftedLatin(label: String): String {
        if (label.length != 1) return label
        val c = label[0]
        if (c in 'a'..'z') return c.uppercaseChar().toString()
        return when (c) {
            '1' -> "!"; '2' -> "@"; '3' -> "#"; '4' -> "$"; '5' -> "%"
            '6' -> "^"; '7' -> "&"; '8' -> "*"; '9' -> "("; '0' -> ")"
            '[' -> "{"; ']' -> "}"; ';' -> ":"; '\'' -> "\""
            ',' -> "<"; '.' -> ">"; '/' -> "?"
            '-' -> "_"; '=' -> "+"; '\\' -> "|"; '`' -> "~"
            else -> label
        }
    }
}
