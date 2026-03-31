package com.jayinlab.koreanime

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Toast

/**
 * Korean IME with physical keyboard support.
 *
 * Key design: onKeyDown MUST return true for any key that onKeyUp will handle.
 * Otherwise the underlying View also processes the DOWN event → double input.
 *
 * Behavior ported from AutoHotkey script:
 *  - ESC            : switch to English if Korean, then send ESC
 *  - CapsLock tap   : toggle Korean/English
 *  - CapsLock hold  : switch to English + ESC (≥250ms)
 *  - Right Alt      : toggle Korean/English (Korean keyboard Han/Eng key)
 *  - Ctrl+J/L/T/R/Y/U/O : switch to English if Korean, then pass key
 *  - Shift+' / ] / G    : switch to English if Korean, then pass key
 *  - Shift+J → Down arrow
 *  - Shift+K → Up arrow
 */
class KoreanIMEService : InputMethodService() {

    private var isKoreanMode = false
    private val composer = KoreanComposer()
    private var capsDownTime = 0L
    // Track shift manually: some devices fire SHIFT_RIGHT UP before the letter UP,
    // causing event.isShiftPressed to return false on the letter's KEY_UP event.
    private var isShiftDown = false

    // 두벌식 normal (no shift)
    private val DUBEOLSIK_NORMAL = mapOf(
        KeyEvent.KEYCODE_Q to 'ㅂ', KeyEvent.KEYCODE_W to 'ㅈ',
        KeyEvent.KEYCODE_E to 'ㄷ', KeyEvent.KEYCODE_R to 'ㄱ',
        KeyEvent.KEYCODE_T to 'ㅅ', KeyEvent.KEYCODE_Y to 'ㅛ',
        KeyEvent.KEYCODE_U to 'ㅕ', KeyEvent.KEYCODE_I to 'ㅑ',
        KeyEvent.KEYCODE_O to 'ㅐ', KeyEvent.KEYCODE_P to 'ㅔ',
        KeyEvent.KEYCODE_A to 'ㅁ', KeyEvent.KEYCODE_S to 'ㄴ',
        KeyEvent.KEYCODE_D to 'ㅇ', KeyEvent.KEYCODE_F to 'ㄹ',
        KeyEvent.KEYCODE_G to 'ㅎ', KeyEvent.KEYCODE_H to 'ㅗ',
        KeyEvent.KEYCODE_J to 'ㅓ', KeyEvent.KEYCODE_K to 'ㅏ',
        KeyEvent.KEYCODE_L to 'ㅣ', KeyEvent.KEYCODE_Z to 'ㅋ',
        KeyEvent.KEYCODE_X to 'ㅌ', KeyEvent.KEYCODE_C to 'ㅊ',
        KeyEvent.KEYCODE_V to 'ㅍ', KeyEvent.KEYCODE_B to 'ㅠ',
        KeyEvent.KEYCODE_N to 'ㅜ', KeyEvent.KEYCODE_M to 'ㅡ'
    )

    // 두벌식 shifted: tensed consonants + ㅒ/ㅖ
    // For keys not in this map, fall back to DUBEOLSIK_NORMAL
    private val DUBEOLSIK_SHIFT = mapOf(
        KeyEvent.KEYCODE_Q to 'ㅃ', KeyEvent.KEYCODE_W to 'ㅉ',
        KeyEvent.KEYCODE_E to 'ㄸ', KeyEvent.KEYCODE_R to 'ㄲ',
        KeyEvent.KEYCODE_T to 'ㅆ', KeyEvent.KEYCODE_O to 'ㅒ',
        KeyEvent.KEYCODE_P to 'ㅖ'
    )

    private val CTRL_ENSURE_ENGLISH = setOf(
        KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_T,
        KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_U,
        KeyEvent.KEYCODE_O
    )

    private val SHIFT_ENSURE_ENGLISH = setOf(
        KeyEvent.KEYCODE_APOSTROPHE,
        KeyEvent.KEYCODE_RIGHT_BRACKET,
        KeyEvent.KEYCODE_G
    )

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        isShiftDown = false
        commitAndReset()
    }

    override fun onFinishInput() {
        commitAndReset()
        super.onFinishInput()
    }

    // ── onKeyDown: MUST consume every key that onKeyUp will handle ────────────
    //
    // If onKeyDown returns false, the underlying View also gets the DOWN event
    // and may insert the English character BEFORE our onKeyUp runs → double input.

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Track shift keys manually before reading isShift
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            isShiftDown = true
            return false  // let system handle shift normally
        }

        val isShift = event.isShiftPressed || isShiftDown
        val isCtrl  = event.isCtrlPressed
        val isMeta  = event.isMetaPressed

        // CapsLock: record press time
        if (keyCode == KeyEvent.KEYCODE_CAPS_LOCK) {
            capsDownTime = SystemClock.uptimeMillis()
            return true
        }

        // Right Alt = Han/Eng (Korean keyboard physical key)
        if (keyCode == KeyEvent.KEYCODE_ALT_RIGHT) return true

        // ESC: always intercept
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) return true

        // Shift+J / Shift+K → direction
        if (isShift && !isCtrl && !isMeta &&
            (keyCode == KeyEvent.KEYCODE_J || keyCode == KeyEvent.KEYCODE_K)) return true

        // Shift+'/]/G → ensure English
        if (isShift && !isCtrl && !isMeta && keyCode in SHIFT_ENSURE_ENGLISH) return true

        // Ctrl+J/L/T/R/Y/U/O → ensure English
        if (isCtrl && !isMeta && keyCode in CTRL_ENSURE_ENGLISH) return true

        // Korean mode: consume any printable key + editing keys
        // (prevents the View from inserting English characters)
        // DEL is only consumed when composing — otherwise let the View delete normally.
        if (isKoreanMode && !isCtrl && !isMeta) {
            val uc = event.getUnicodeChar(event.metaState)
            if (uc > 0 ||
                (keyCode == KeyEvent.KEYCODE_DEL && composer.isComposing) ||
                keyCode == KeyEvent.KEYCODE_SPACE ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_TAB) {
                return true
            }
        }

        return false
    }

    // ── onKeyUp: actual key handling ─────────────────────────────────────────

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Track shift keys manually
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            isShiftDown = false
            return false
        }

        val isShift = event.isShiftPressed || isShiftDown
        val isCtrl  = event.isCtrlPressed
        val isMeta  = event.isMetaPressed

        // CapsLock: short tap = toggle, long press = force English + ESC
        if (keyCode == KeyEvent.KEYCODE_CAPS_LOCK) {
            val held = SystemClock.uptimeMillis() - capsDownTime
            if (held >= 250) {
                switchToEnglish()
                sendKey(KeyEvent.KEYCODE_ESCAPE)
            } else {
                toggleKoreanMode()
            }
            return true
        }

        // Right Alt = Han/Eng toggle
        if (keyCode == KeyEvent.KEYCODE_ALT_RIGHT) {
            toggleKoreanMode()
            return true
        }

        // ESC: force English then forward ESC
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            switchToEnglish()
            sendKey(KeyEvent.KEYCODE_ESCAPE)
            return true
        }

        // Shift+J → Down, Shift+K → Up
        if (isShift && !isCtrl && !isMeta) {
            if (keyCode == KeyEvent.KEYCODE_J) {
                commitAndReset()
                sendKey(KeyEvent.KEYCODE_DPAD_DOWN)
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_K) {
                commitAndReset()
                sendKey(KeyEvent.KEYCODE_DPAD_UP)
                return true
            }
        }

        // Ctrl+J/L/T/R/Y/U/O → ensure English then pass through
        if (isCtrl && !isMeta && keyCode in CTRL_ENSURE_ENGLISH) {
            switchToEnglish()
            passThrough(keyCode, event)
            return true
        }

        // Shift+'/]/G → ensure English then pass through
        if (isShift && !isCtrl && !isMeta && keyCode in SHIFT_ENSURE_ENGLISH) {
            switchToEnglish()
            passThrough(keyCode, event)
            return true
        }

        // Korean character input
        if (!isCtrl && !isMeta && isKoreanMode) {
            return handleKoreanKey(keyCode, isShift, event)
        }

        return super.onKeyUp(keyCode, event)
    }

    // ── Korean input logic ───────────────────────────────────────────────────

    private fun handleKoreanKey(keyCode: Int, isShift: Boolean, event: KeyEvent): Boolean {
        val ic = currentInputConnection ?: return false

        // Backspace
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            if (composer.isComposing) {
                val (deleteCount, newComposing) = composer.backspace()
                if (deleteCount > 0) ic.deleteSurroundingText(deleteCount, 0)
                if (newComposing.isEmpty()) {
                    ic.setComposingText("", 0)
                    ic.finishComposingText()
                } else {
                    ic.setComposingText(newComposing, 1)
                }
                return true
            }
            return false // let system delete when nothing composing
        }

        // Enter / Tab: commit then forward the key preserving meta state (e.g. Shift+Tab = dedent)
        // (onKeyDown already consumed the DOWN event, so we must forward UP ourselves)
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_TAB) {
            commitAndReset()
            passThrough(keyCode, event)
            return true
        }

        // Space: commit then insert space
        if (keyCode == KeyEvent.KEYCODE_SPACE) {
            val committed = composer.flush()
            if (committed.isNotEmpty()) {
                ic.commitText(committed, 1)
            }
            ic.finishComposingText()
            ic.commitText(" ", 1)
            return true
        }

        // Korean jamo lookup. For shifted consonants without a tensed form,
        // fall back to the normal (unshifted) jamo — standard 두벌식 behaviour.
        val jamo = if (isShift) DUBEOLSIK_SHIFT[keyCode] ?: DUBEOLSIK_NORMAL[keyCode]
                   else DUBEOLSIK_NORMAL[keyCode]

        if (jamo != null) {
            val (commit, newComposing) = composer.input(jamo)
            if (commit.isNotEmpty()) {
                ic.commitText(commit, 1)
            }
            if (newComposing.isEmpty()) ic.finishComposingText()
            else ic.setComposingText(newComposing, 1)
            return true
        }

        // Non-jamo key (number, punctuation…): commit composing, insert char directly
        commitAndReset()
        val uc = event.getUnicodeChar(event.metaState)
        if (uc > 0) {
            ic.commitText(String(Character.toChars(uc)), 1)
            return true
        }
        return false
    }

    // ── Mode switching ───────────────────────────────────────────────────────

    private fun toggleKoreanMode() {
        commitAndReset()
        isKoreanMode = !isKoreanMode
        Toast.makeText(this, if (isKoreanMode) "한글" else "ENG", Toast.LENGTH_SHORT).show()
    }

    private fun switchToEnglish() {
        if (isKoreanMode) {
            commitAndReset()
            isKoreanMode = false
            Toast.makeText(this, "ENG", Toast.LENGTH_SHORT).show()
        }
    }

    private fun commitAndReset() {
        val ic = currentInputConnection ?: return
        val text = composer.flush()
        if (text.isNotEmpty()) ic.commitText(text, 1)
        ic.finishComposingText()
    }

    // ── Key forwarding helpers ───────────────────────────────────────────────

    private fun sendKey(keyCode: Int) {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun passThrough(keyCode: Int, originalEvent: KeyEvent) {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(originalEvent.downTime, now, KeyEvent.ACTION_DOWN, keyCode, 0, originalEvent.metaState))
        ic.sendKeyEvent(KeyEvent(originalEvent.downTime, now, KeyEvent.ACTION_UP, keyCode, 0, originalEvent.metaState))
    }
}
