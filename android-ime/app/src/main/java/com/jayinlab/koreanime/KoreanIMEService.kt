package com.jayinlab.koreanime

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.KeyEvent
import android.view.KeyEvent.*
import android.view.inputmethod.EditorInfo
import android.widget.Toast

/**
 * Korean IME with physical keyboard support.
 *
 * Key design:
 * - ALL key handling (except CapsLock timing) is done in onKeyDown, NOT onKeyUp.
 *   Reason: fast typing causes KEY_UP events to fire in a different order than
 *   KEY_DOWN events. Processing in KEY_DOWN guarantees press-order correctness
 *   (e.g. ㅈ→ㅗ→ㅁ = 좀, never 옺ㅁ).
 * - onKeyDown returns true for any key we handle, so the View never gets it.
 * - onKeyUp mirrors those same conditions and returns true to consume the UP,
 *   but does no work (except CapsLock which needs hold-duration).
 * - All InputConnection calls are wrapped in beginBatchEdit/endBatchEdit for
 *   atomic UI updates and better performance.
 */
class KoreanIMEService : InputMethodService() {

    private var isKoreanMode = false
    private val composer = KoreanComposer()
    private var capsDownTime = 0L
    private var isShiftDown = false   // manual shift tracking (right-shift timing fix)

    private val DUBEOLSIK_NORMAL = mapOf(
        KEYCODE_Q to 'ㅂ', KEYCODE_W to 'ㅈ',
        KEYCODE_E to 'ㄷ', KEYCODE_R to 'ㄱ',
        KEYCODE_T to 'ㅅ', KEYCODE_Y to 'ㅛ',
        KEYCODE_U to 'ㅕ', KEYCODE_I to 'ㅑ',
        KEYCODE_O to 'ㅐ', KEYCODE_P to 'ㅔ',
        KEYCODE_A to 'ㅁ', KEYCODE_S to 'ㄴ',
        KEYCODE_D to 'ㅇ', KEYCODE_F to 'ㄹ',
        KEYCODE_G to 'ㅎ', KEYCODE_H to 'ㅗ',
        KEYCODE_J to 'ㅓ', KEYCODE_K to 'ㅏ',
        KEYCODE_L to 'ㅣ', KEYCODE_Z to 'ㅋ',
        KEYCODE_X to 'ㅌ', KEYCODE_C to 'ㅊ',
        KEYCODE_V to 'ㅍ', KEYCODE_B to 'ㅠ',
        KEYCODE_N to 'ㅜ', KEYCODE_M to 'ㅡ'
    )

    private val DUBEOLSIK_SHIFT = mapOf(
        KEYCODE_Q to 'ㅃ', KEYCODE_W to 'ㅉ',
        KEYCODE_E to 'ㄸ', KEYCODE_R to 'ㄲ',
        KEYCODE_T to 'ㅆ', KEYCODE_O to 'ㅒ',
        KEYCODE_P to 'ㅖ'
    )

    private val CTRL_ENSURE_ENGLISH = setOf(
        KEYCODE_J, KEYCODE_L, KEYCODE_T, KEYCODE_R, KEYCODE_Y, KEYCODE_U, KEYCODE_O
    )

    private val SHIFT_ENSURE_ENGLISH = setOf(
        KEYCODE_APOSTROPHE, KEYCODE_RIGHT_BRACKET, KEYCODE_G
    )

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        isShiftDown = false
        commitAndReset()
    }

    override fun onFinishInput() {
        commitAndReset()
        super.onFinishInput()
    }

    // ── onKeyDown: ALL key handling happens here ───────────────────────────────
    //
    // Processing on KEY_DOWN guarantees that characters are inserted in the exact
    // order the keys were pressed, regardless of release order.

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Track shift manually so isShift is reliable even at high typing speed
        if (keyCode == KEYCODE_SHIFT_LEFT || keyCode == KEYCODE_SHIFT_RIGHT) {
            isShiftDown = true
            return false  // let system track shift for its own meta state
        }

        val isShift = event.isShiftPressed || isShiftDown
        val isCtrl  = event.isCtrlPressed
        val isMeta  = event.isMetaPressed

        // CapsLock: record press time; actual action fires on KEY_UP (needs duration)
        if (keyCode == KEYCODE_CAPS_LOCK) {
            capsDownTime = SystemClock.uptimeMillis()
            return true
        }

        // Suppress key repeats in Korean mode (holding a key should not re-insert jamo).
        // DEL repeats are allowed to fall through so held-delete deletes multiple chars.
        if (event.repeatCount > 0 && isKoreanMode && !isCtrl && !isMeta) {
            val uc = event.getUnicodeChar(event.metaState)
            if (uc > 0 || keyCode == KEYCODE_SPACE ||
                keyCode == KEYCODE_ENTER || keyCode == KEYCODE_TAB) return true
        }

        // Right Alt = Han/Eng toggle
        if (keyCode == KEYCODE_ALT_RIGHT) {
            toggleKoreanMode()
            return true
        }

        // ESC: force English then forward ESC
        if (keyCode == KEYCODE_ESCAPE) {
            switchToEnglish()
            sendKey(KEYCODE_ESCAPE)
            return true
        }

        // Shift+J → Down, Shift+K → Up (English mode only)
        // In Korean mode J=ㅓ and K=ㅏ, so Shift+J/K must fall through to jamo handling.
        // Without this guard, typing e.g. ㄲ(Shift+R) then ㅏ(Shift+K) would move the cursor
        // instead of composing '까'. Affected: 까꺼따떠짜쩌빠뻐싸써 etc.
        if (isShift && !isCtrl && !isMeta && !isKoreanMode) {
            if (keyCode == KEYCODE_J) { sendKey(KEYCODE_DPAD_DOWN); return true }
            if (keyCode == KEYCODE_K) { sendKey(KEYCODE_DPAD_UP);   return true }
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

        return false
    }

    // ── onKeyUp: CapsLock timing only; consume keys handled in onKeyDown ──────

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KEYCODE_SHIFT_LEFT || keyCode == KEYCODE_SHIFT_RIGHT) {
            isShiftDown = false
            return false
        }

        val isShift = event.isShiftPressed || isShiftDown
        val isCtrl  = event.isCtrlPressed
        val isMeta  = event.isMetaPressed

        // CapsLock: short tap = toggle Han/Eng, long press (≥250ms) = English + ESC
        if (keyCode == KEYCODE_CAPS_LOCK) {
            val held = SystemClock.uptimeMillis() - capsDownTime
            if (held >= 250) { switchToEnglish(); sendKey(KEYCODE_ESCAPE) }
            else toggleKoreanMode()
            return true
        }

        // Consume UP events for all keys that were handled in onKeyDown
        if (keyCode == KEYCODE_ALT_RIGHT) return true
        if (keyCode == KEYCODE_ESCAPE)    return true
        if (isShift && !isCtrl && !isMeta && !isKoreanMode &&
            (keyCode == KEYCODE_J || keyCode == KEYCODE_K)) return true
        if (isCtrl  && !isMeta && keyCode in CTRL_ENSURE_ENGLISH)  return true
        if (isShift && !isCtrl && !isMeta && keyCode in SHIFT_ENSURE_ENGLISH) return true
        if (!isCtrl && !isMeta && isKoreanMode) {
            val uc = event.getUnicodeChar(event.metaState)
            if (uc > 0 || (keyCode == KEYCODE_DEL && composer.isComposing) ||
                keyCode == KEYCODE_SPACE || keyCode == KEYCODE_ENTER || keyCode == KEYCODE_TAB)
                return true
        }

        return super.onKeyUp(keyCode, event)
    }

    // ── Korean input logic ────────────────────────────────────────────────────

    private fun handleKoreanKey(keyCode: Int, isShift: Boolean, event: KeyEvent): Boolean {
        val ic = currentInputConnection ?: return false
        ic.beginBatchEdit()
        var result = false
        try {
            result = when {
                // Backspace
                keyCode == KEYCODE_DEL -> {
                    if (composer.isComposing) {
                        val (deleteCount, newComposing) = composer.backspace()
                        if (deleteCount > 0) ic.deleteSurroundingText(deleteCount, 0)
                        // setComposingText("") clears the composing region before finishing.
                        // Without this, finishComposingText() would commit the old composing
                        // text (e.g. "ㅇ") as regular text, requiring a second backspace.
                        if (newComposing.isEmpty()) {
                            ic.setComposingText("", 1)
                            ic.finishComposingText()
                        } else {
                            ic.setComposingText(newComposing, 1)
                        }
                        true
                    } else false  // let system handle backspace when nothing is composing
                }

                // Enter / Tab: commit composing then forward key with meta state preserved
                keyCode == KEYCODE_ENTER || keyCode == KEYCODE_TAB -> {
                    commitAndReset()
                    passThrough(keyCode, event)
                    true
                }

                // Space: flush composer then insert space
                keyCode == KEYCODE_SPACE -> {
                    val committed = composer.flush()
                    if (committed.isNotEmpty()) ic.commitText(committed, 1)
                    ic.finishComposingText()
                    ic.commitText(" ", 1)
                    true
                }

                // Jamo key
                else -> {
                    val jamo = if (isShift) DUBEOLSIK_SHIFT[keyCode] ?: DUBEOLSIK_NORMAL[keyCode]
                               else DUBEOLSIK_NORMAL[keyCode]
                    if (jamo != null) {
                        val (commit, newComposing) = composer.input(jamo)
                        if (commit.isNotEmpty()) ic.commitText(commit, 1)
                        if (newComposing.isEmpty()) ic.finishComposingText()
                        else ic.setComposingText(newComposing, 1)
                        true
                    } else {
                        // Non-jamo printable (numbers, punctuation): commit composing first
                        commitAndReset()
                        val uc = event.getUnicodeChar(event.metaState)
                        if (uc > 0) { ic.commitText(String(Character.toChars(uc)), 1); true }
                        else false
                    }
                }
            }
        } finally {
            ic.endBatchEdit()
        }
        return result
    }

    // ── Mode switching ────────────────────────────────────────────────────────

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
        ic.beginBatchEdit()
        try {
            val text = composer.flush()
            if (text.isNotEmpty()) ic.commitText(text, 1)
            ic.finishComposingText()
        } finally {
            ic.endBatchEdit()
        }
    }

    // ── Key forwarding ────────────────────────────────────────────────────────

    private fun sendKey(keyCode: Int) {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, ACTION_DOWN, keyCode, 0))
        ic.sendKeyEvent(KeyEvent(now, now, ACTION_UP,   keyCode, 0))
    }

    private fun passThrough(keyCode: Int, originalEvent: KeyEvent) {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(originalEvent.downTime, now, ACTION_DOWN,
                                 keyCode, 0, originalEvent.metaState))
        ic.sendKeyEvent(KeyEvent(originalEvent.downTime, now, ACTION_UP,
                                 keyCode, 0, originalEvent.metaState))
    }
}
