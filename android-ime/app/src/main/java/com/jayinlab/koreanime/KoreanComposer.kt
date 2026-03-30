package com.jayinlab.koreanime

/**
 * 두벌식 Korean syllable composition engine.
 *
 * State machine: EMPTY → CHO → CHO_JUNG → CHO_JUNG_JONG
 *
 * Returns composing text for setComposingText() and commit strings.
 */
class KoreanComposer {

    // ── Jamo tables ──────────────────────────────────────────────────────────

    companion object {
        // 초성 (19 entries)
        val CHO = charArrayOf(
            'ㄱ','ㄲ','ㄴ','ㄷ','ㄸ','ㄹ','ㅁ','ㅂ','ㅃ','ㅅ',
            'ㅆ','ㅇ','ㅈ','ㅉ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ'
        )
        // 중성 (21 entries)
        val JUNG = charArrayOf(
            'ㅏ','ㅐ','ㅑ','ㅒ','ㅓ','ㅔ','ㅕ','ㅖ','ㅗ','ㅘ','ㅙ','ㅚ',
            'ㅛ','ㅜ','ㅝ','ㅞ','ㅟ','ㅠ','ㅡ','ㅢ','ㅣ'
        )
        // 종성 (28 entries, 0 = none)
        val JONG = charArrayOf(
            ' ','ㄱ','ㄲ','ㄳ','ㄴ','ㄵ','ㄶ','ㄷ','ㄹ','ㄺ','ㄻ','ㄼ','ㄽ','ㄾ','ㄿ','ㅀ',
            'ㅁ','ㅂ','ㅄ','ㅅ','ㅆ','ㅇ','ㅈ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ'
        )

        // consonant char → 초성 index (-1 if not valid 초성)
        private val CHAR_TO_CHO = mapOf(
            'ㄱ' to 0, 'ㄲ' to 1, 'ㄴ' to 2, 'ㄷ' to 3, 'ㄸ' to 4,
            'ㄹ' to 5, 'ㅁ' to 6, 'ㅂ' to 7, 'ㅃ' to 8, 'ㅅ' to 9,
            'ㅆ' to 10, 'ㅇ' to 11, 'ㅈ' to 12, 'ㅉ' to 13, 'ㅊ' to 14,
            'ㅋ' to 15, 'ㅌ' to 16, 'ㅍ' to 17, 'ㅎ' to 18
        )
        // consonant char → 종성 index (0 = none)
        private val CHAR_TO_JONG = mapOf(
            'ㄱ' to 1, 'ㄲ' to 2, 'ㄴ' to 4, 'ㄷ' to 7, 'ㄹ' to 8,
            'ㅁ' to 16, 'ㅂ' to 17, 'ㅅ' to 19, 'ㅆ' to 20, 'ㅇ' to 21,
            'ㅈ' to 22, 'ㅊ' to 23, 'ㅋ' to 24, 'ㅌ' to 25, 'ㅍ' to 26, 'ㅎ' to 27
        )
        // vowel char → 중성 index
        private val CHAR_TO_JUNG = mapOf(
            'ㅏ' to 0, 'ㅐ' to 1, 'ㅑ' to 2, 'ㅒ' to 3, 'ㅓ' to 4,
            'ㅔ' to 5, 'ㅕ' to 6, 'ㅖ' to 7, 'ㅗ' to 8, 'ㅘ' to 9,
            'ㅙ' to 10, 'ㅚ' to 11, 'ㅛ' to 12, 'ㅜ' to 13, 'ㅝ' to 14,
            'ㅞ' to 15, 'ㅟ' to 16, 'ㅠ' to 17, 'ㅡ' to 18, 'ㅢ' to 19, 'ㅣ' to 20
        )

        // compound 종성: (first, second) → combined jong index
        private val JONG_COMPOUND = mapOf(
            Pair(1, 19) to 3,   // ㄱ+ㅅ = ㄳ
            Pair(4, 22) to 5,   // ㄴ+ㅈ = ㄵ
            Pair(4, 27) to 6,   // ㄴ+ㅎ = ㄶ
            Pair(8, 1) to 9,    // ㄹ+ㄱ = ㄺ
            Pair(8, 16) to 10,  // ㄹ+ㅁ = ㄻ
            Pair(8, 17) to 11,  // ㄹ+ㅂ = ㄼ
            Pair(8, 19) to 12,  // ㄹ+ㅅ = ㄽ
            Pair(8, 25) to 13,  // ㄹ+ㅌ = ㄾ
            Pair(8, 26) to 14,  // ㄹ+ㅍ = ㄿ
            Pair(8, 27) to 15,  // ㄹ+ㅎ = ㅀ
            Pair(17, 19) to 18  // ㅂ+ㅅ = ㅄ
        )

        // compound 중성: (first, second) → combined jung index
        private val JUNG_COMPOUND = mapOf(
            Pair(8, 0) to 9,    // ㅗ+ㅏ = ㅘ
            Pair(8, 1) to 10,   // ㅗ+ㅐ = ㅙ
            Pair(8, 20) to 11,  // ㅗ+ㅣ = ㅚ
            Pair(13, 4) to 14,  // ㅜ+ㅓ = ㅝ
            Pair(13, 5) to 15,  // ㅜ+ㅔ = ㅞ
            Pair(13, 20) to 16, // ㅜ+ㅣ = ㅟ
            Pair(18, 20) to 19  // ㅡ+ㅣ = ㅢ
        )

        // decompose compound 종성 → (first jong index, second jong index as 초성)
        private val JONG_DECOMPOSE = mapOf(
            3 to Pair(1, 19),   // ㄳ → ㄱ, ㅅ
            5 to Pair(4, 22),   // ㄵ → ㄴ, ㅈ
            6 to Pair(4, 27),   // ㄶ → ㄴ, ㅎ
            9 to Pair(8, 1),    // ㄺ → ㄹ, ㄱ
            10 to Pair(8, 16),  // ㄻ → ㄹ, ㅁ
            11 to Pair(8, 17),  // ㄼ → ㄹ, ㅂ
            12 to Pair(8, 19),  // ㄽ → ㄹ, ㅅ
            13 to Pair(8, 25),  // ㄾ → ㄹ, ㅌ
            14 to Pair(8, 26),  // ㄿ → ㄹ, ㅍ
            15 to Pair(8, 27),  // ㅀ → ㄹ, ㅎ
            18 to Pair(17, 19)  // ㅄ → ㅂ, ㅅ
        )

        // jong index → 초성 index (same consonant, different table position)
        private val JONG_TO_CHO = mapOf(
            1 to 0, 2 to 1, 4 to 2, 7 to 3, 8 to 5, 16 to 6, 17 to 7,
            19 to 9, 20 to 10, 21 to 11, 22 to 12, 23 to 14, 24 to 15,
            25 to 16, 26 to 17, 27 to 18
        )

        fun syllable(cho: Int, jung: Int, jong: Int): Char =
            (0xAC00 + cho * 21 * 28 + jung * 28 + jong).toChar()
    }

    // ── State ────────────────────────────────────────────────────────────────

    private enum class State { EMPTY, CHO, CHO_JUNG, CHO_JUNG_JONG }

    private var state = State.EMPTY
    private var choIdx = 0
    private var jungIdx = 0
    private var jongIdx = 0   // 0 = no jong

    /** Current composing character for display */
    val composing: String
        get() = when (state) {
            State.EMPTY -> ""
            State.CHO -> CHO[choIdx].toString()
            State.CHO_JUNG -> syllable(choIdx, jungIdx, 0).toString()
            State.CHO_JUNG_JONG -> syllable(choIdx, jungIdx, jongIdx).toString()
        }

    /** True if there is an ongoing composition */
    val isComposing: Boolean get() = state != State.EMPTY

    // ── Input handling ───────────────────────────────────────────────────────

    /**
     * Feed a jamo character into the composer.
     * @return Pair(commit, newComposing)
     *   commit: text to commit immediately before the new composing text
     *   newComposing: the new composing text (empty string = nothing composing)
     */
    fun input(jamo: Char): Pair<String, String> {
        val isVowel = CHAR_TO_JUNG.containsKey(jamo)
        val isConsonant = CHAR_TO_CHO.containsKey(jamo)

        return when (state) {
            State.EMPTY -> {
                if (isConsonant) {
                    choIdx = CHAR_TO_CHO[jamo]!!
                    state = State.CHO
                    Pair("", CHO[choIdx].toString())
                } else if (isVowel) {
                    // no 초성 → output as standalone vowel (ㅇ as silent 초성)
                    jungIdx = CHAR_TO_JUNG[jamo]!!
                    choIdx = 11 // ㅇ (silent)
                    state = State.CHO_JUNG
                    Pair("", syllable(11, jungIdx, 0).toString())
                } else {
                    Pair(jamo.toString(), "")
                }
            }

            State.CHO -> {
                if (isVowel) {
                    jungIdx = CHAR_TO_JUNG[jamo]!!
                    state = State.CHO_JUNG
                    Pair("", syllable(choIdx, jungIdx, 0).toString())
                } else if (isConsonant) {
                    // commit current CHO, start new CHO
                    val commit = CHO[choIdx].toString()
                    choIdx = CHAR_TO_CHO[jamo]!!
                    state = State.CHO
                    Pair(commit, CHO[choIdx].toString())
                } else {
                    val commit = CHO[choIdx].toString()
                    reset()
                    Pair(commit, "")
                }
            }

            State.CHO_JUNG -> {
                if (isConsonant) {
                    val jongI = CHAR_TO_JONG[jamo]
                    if (jongI != null) {
                        jongIdx = jongI
                        state = State.CHO_JUNG_JONG
                        Pair("", syllable(choIdx, jungIdx, jongIdx).toString())
                    } else {
                        // tensed consonants (ㄸ,ㅃ,ㅉ) can't be 종성
                        val commit = syllable(choIdx, jungIdx, 0).toString()
                        choIdx = CHAR_TO_CHO[jamo]!!
                        jungIdx = 0
                        state = State.CHO
                        Pair(commit, CHO[choIdx].toString())
                    }
                } else if (isVowel) {
                    val vIdx = CHAR_TO_JUNG[jamo]!!
                    val compound = JUNG_COMPOUND[Pair(jungIdx, vIdx)]
                    if (compound != null) {
                        jungIdx = compound
                        Pair("", syllable(choIdx, jungIdx, 0).toString())
                    } else {
                        // new syllable with ㅇ as silent 초성
                        val commit = syllable(choIdx, jungIdx, 0).toString()
                        choIdx = 11; jungIdx = vIdx
                        state = State.CHO_JUNG
                        Pair(commit, syllable(11, jungIdx, 0).toString())
                    }
                } else {
                    val commit = syllable(choIdx, jungIdx, 0).toString()
                    reset()
                    Pair(commit, "")
                }
            }

            State.CHO_JUNG_JONG -> {
                if (isVowel) {
                    // 종성 splits: becomes 초성 of new syllable
                    val vIdx = CHAR_TO_JUNG[jamo]!!
                    val decompose = JONG_DECOMPOSE[jongIdx]
                    val newChoIdx: Int
                    val newJongIdx: Int
                    if (decompose != null) {
                        newJongIdx = decompose.first
                        newChoIdx = JONG_TO_CHO[decompose.second]!!
                    } else {
                        newJongIdx = 0
                        newChoIdx = JONG_TO_CHO[jongIdx]!!
                    }
                    val commit = syllable(choIdx, jungIdx, newJongIdx).toString()
                    choIdx = newChoIdx; jungIdx = vIdx; jongIdx = 0
                    state = State.CHO_JUNG
                    Pair(commit, syllable(choIdx, jungIdx, 0).toString())
                } else if (isConsonant) {
                    // try compound 종성
                    val addJongI = CHAR_TO_JONG[jamo]
                    val compound = if (addJongI != null) JONG_COMPOUND[Pair(jongIdx, addJongI)] else null
                    if (compound != null) {
                        jongIdx = compound
                        state = State.CHO_JUNG_JONG
                        Pair("", syllable(choIdx, jungIdx, jongIdx).toString())
                    } else {
                        // commit current, new CHO
                        val commit = syllable(choIdx, jungIdx, jongIdx).toString()
                        choIdx = CHAR_TO_CHO[jamo]!!
                        jungIdx = 0; jongIdx = 0
                        state = State.CHO
                        Pair(commit, CHO[choIdx].toString())
                    }
                } else {
                    val commit = syllable(choIdx, jungIdx, jongIdx).toString()
                    reset()
                    Pair(commit, "")
                }
            }
        }
    }

    /**
     * Handle backspace within composition.
     * @return Pair(deleteCount, newComposing)
     *   deleteCount: how many chars to delete from input connection
     *   newComposing: new composing string
     */
    fun backspace(): Pair<Int, String> {
        return when (state) {
            State.EMPTY -> Pair(1, "")  // let system handle
            State.CHO -> {
                reset()
                Pair(0, "")  // composing char removed, commit nothing
            }
            State.CHO_JUNG -> {
                // check if jung is compound → decompose
                val decomposed = decomposeJung(jungIdx)
                if (decomposed != null) {
                    jungIdx = decomposed
                    Pair(0, syllable(choIdx, jungIdx, 0).toString())
                } else {
                    state = State.CHO
                    Pair(0, CHO[choIdx].toString())
                }
            }
            State.CHO_JUNG_JONG -> {
                val decomposed = JONG_DECOMPOSE[jongIdx]
                if (decomposed != null) {
                    jongIdx = decomposed.first
                    Pair(0, syllable(choIdx, jungIdx, jongIdx).toString())
                } else {
                    state = State.CHO_JUNG
                    jongIdx = 0
                    Pair(0, syllable(choIdx, jungIdx, 0).toString())
                }
            }
        }
    }

    /** Flush composing text as commit string. */
    fun flush(): String {
        val result = composing
        reset()
        return result
    }

    fun reset() {
        state = State.EMPTY
        choIdx = 0; jungIdx = 0; jongIdx = 0
    }

    private fun decomposeJung(idx: Int): Int? {
        return when (idx) {
            9 -> 8   // ㅘ → ㅗ
            10 -> 8  // ㅙ → ㅗ
            11 -> 8  // ㅚ → ㅗ
            14 -> 13 // ㅝ → ㅜ
            15 -> 13 // ㅞ → ㅜ
            16 -> 13 // ㅟ → ㅜ
            19 -> 18 // ㅢ → ㅡ
            else -> null
        }
    }
}
