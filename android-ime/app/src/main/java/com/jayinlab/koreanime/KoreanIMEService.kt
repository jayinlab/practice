package com.jayinlab.koreanime

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo

/**
 * Korean IME with physical keyboard support.
 *
 * Replicates the AutoHotkey script behavior:
 *  - ESC         : if Korean mode → switch to English, then send ESC
 *  - CapsLock    : short tap (<250ms) → toggle Korean/English
 *                  long press (≥250ms) → switch to English + send ESC
 *  - Ctrl+J/L/T/R/Y/U/O : if Korean → switch to English, then pass through
 *  - Shift+' / Shift+] / Shift+G : same pattern
 *  - Shift+J → Down arrow
 *  - Shift+K → Up arrow
 *
 * 두벌식 keyboard layout is used for Korean input.
 */
class KoreanIMEService : InputMethodService() {

    // ── State ────────────────────────────────────────────────────────────────

    private var isKoreanMode = false
    private val composer = KoreanComposer()

    // CapsLock timing
    private var capsDownTime = 0L

    // ── Keyboard layout map (두벌식) ─────────────────────────────────────────

    /** Maps KeyEvent keyCode → Korean jamo character (normal, no shift) */
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

    /** Maps KeyEvent keyCode → Korean jamo character (with shift) */
    private val DUBEOLSIK_SHIFT = mapOf(
        KeyEvent.KEYCODE_Q to 'ㅃ', KeyEvent.KEYCODE_W to 'ㅉ',
        KeyEvent.KEYCODE_E to 'ㄸ', KeyEvent.KEYCODE_R to 'ㄲ',
        KeyEvent.KEYCODE_T to 'ㅆ', KeyEvent.KEYCODE_O to 'ㅒ',
        KeyEvent.KEYCODE_P to 'ㅖ'
    )

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        commitAndReset()
    }

    override fun onFinishInput() {
        commitAndReset()
        super.onFinishInput()
    }

    // ── Key event handling ───────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Track CapsLock press time
        if (keyCode == KeyEvent.KEYCODE_CAPS_LOCK) {
            capsDownTime = SystemClock.uptimeMillis()
            return true
        }
        return false  // let onKeyUp handle most keys
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val isShift = event.isShiftPressed
        val isCtrl  = event.isCtrlPressed
        val isMeta  = event.isMetaPressed

        // ── CapsLock ──────────────────────────────────────────────────────
        if (keyCode == KeyEvent.KEYCODE_CAPS_LOCK) {
            val held = SystemClock.uptimeMillis() - capsDownTime
            if (held >= 250) {
                // Long press: switch to English + send ESC
                switchToEnglish()
                sendEscape()
            } else {
                // Short tap: toggle Korean/English
                toggleKoreanMode()
            }
            return true
        }

        // ── ESC: switch to English then send ESC ─────────────────────────
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            switchToEnglish()
            sendEscape()
            return true
        }

        // ── Shift+J → Down, Shift+K → Up ─────────────────────────────────
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

        // ── Ctrl combos: ensure English before passing through ────────────
        if (isCtrl && !isMeta) {
            val ctrlKeys = setOf(
                KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_T,
                KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_U,
                KeyEvent.KEYCODE_O
            )
            if (keyCode in ctrlKeys) {
                switchToEnglish()
                passThrough(keyCode, event)
                return true
            }
        }

        // ── Shift+' / Shift+] / Shift+G: ensure English ──────────────────
        if (isShift && !isCtrl && !isMeta) {
            val shiftEnsureEnglish = setOf(
                KeyEvent.KEYCODE_APOSTROPHE,
                KeyEvent.KEYCODE_RIGHT_BRACKET,
                KeyEvent.KEYCODE_G
            )
            if (keyCode in shiftEnsureEnglish) {
                switchToEnglish()
                passThrough(keyCode, event)
                return true
            }
        }

        // ── Normal character input ────────────────────────────────────────
        if (!isCtrl && !isMeta) {
            if (isKoreanMode) {
                return handleKoreanKey(keyCode, isShift)
            }
            // English mode: let system handle
        }

        return super.onKeyUp(keyCode, event)
    }

    // ── Korean input logic ───────────────────────────────────────────────────

    private fun handleKoreanKey(keyCode: Int, isShift: Boolean): Boolean {
        val ic = currentInputConnection ?: return false

        // Backspace during composition
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            if (composer.isComposing) {
                val (deleteCount, newComposing) = composer.backspace()
                if (deleteCount > 0) {
                    // Delete already-committed chars
                    ic.deleteSurroundingText(deleteCount, 0)
                }
                if (newComposing.isEmpty()) {
                    ic.setComposingText("", 0)
                    ic.finishComposingText()
                } else {
                    ic.setComposingText(newComposing, 1)
                }
                return true
            }
            // Not composing: pass through
            return false
        }

        // Enter/Tab: commit and pass through
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_TAB) {
            commitAndReset()
            return false
        }

        // Space: commit current composition then insert space
        if (keyCode == KeyEvent.KEYCODE_SPACE) {
            val committed = composer.flush()
            if (committed.isNotEmpty()) {
                ic.commitText(committed, 1)
                ic.finishComposingText()
            }
            ic.commitText(" ", 1)
            return true
        }

        // Try Korean jamo
        val jamo = if (isShift) DUBEOLSIK_SHIFT[keyCode] else DUBEOLSIK_NORMAL[keyCode]
        if (jamo != null) {
            val (commit, newComposing) = composer.input(jamo)
            if (commit.isNotEmpty()) {
                ic.commitText(commit, 1)
                ic.finishComposingText()
            }
            if (newComposing.isEmpty()) {
                ic.finishComposingText()
            } else {
                ic.setComposingText(newComposing, 1)
            }
            return true
        }

        // Non-Korean key (numbers, punctuation): commit first
        commitAndReset()
        return false
    }

    // ── Mode switching ───────────────────────────────────────────────────────

    private fun toggleKoreanMode() {
        commitAndReset()
        isKoreanMode = !isKoreanMode
        // Show brief toast-like notification via IME status
        showModeNotification()
    }

    private fun switchToEnglish() {
        if (isKoreanMode) {
            commitAndReset()
            isKoreanMode = false
            showModeNotification()
        }
    }

    private fun commitAndReset() {
        val ic = currentInputConnection ?: return
        val text = composer.flush()
        if (text.isNotEmpty()) {
            ic.commitText(text, 1)
        }
        ic.finishComposingText()
    }

    // ── Key sending helpers ──────────────────────────────────────────────────

    private fun sendEscape() {
        sendKey(KeyEvent.KEYCODE_ESCAPE)
    }

    private fun sendKey(keyCode: Int) {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun passThrough(keyCode: Int, originalEvent: KeyEvent) {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(originalEvent, KeyEvent.ACTION_DOWN, 0))
        ic.sendKeyEvent(KeyEvent(originalEvent, KeyEvent.ACTION_UP, 0))
    }

    // ── UI notification ──────────────────────────────────────────────────────

    private fun showModeNotification() {
        // Show mode change in the candidates bar area
        val modeText = if (isKoreanMode) "한글" else "ENG"
        setCandidatesViewShown(true)
        // The candidates view shows current mode
        // We'll use the window token approach for a simple toast
        android.widget.Toast.makeText(
            this,
            modeText,
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}
