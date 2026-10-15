/*
 * This file is part of SDL3 android-project java code.
 * Licensed under the zlib license: https://www.libsdl.org/license.php
 */

package org.libsdl.app;

import android.text.Editable;
import android.text.Selection;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.widget.EditText;

import androidx.annotation.Keep;

import com.movtery.zalithlauncher.game.input.LWJGLCharSender;
import com.movtery.zalithlauncher.game.sdl.SdlBridge;
import org.lwjgl.glfw.CallbackBridge;

@Keep
class SDLInputConnection extends BaseInputConnection
{
    protected EditText mEditText;
    protected String mCommittedText = "";
    private int batchEditDepth = 0;
    private boolean textUpdatePending = false;
    private int pendingExternalBackspaces = 0;

    SDLInputConnection(View targetView, boolean fullEditor) {
        super(targetView, fullEditor);
        mEditText = new EditText(SDL.getContext());
    }

    @Override
    public Editable getEditable() {
        return mEditText.getEditableText();
    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {
        /*
         * This used to handle the keycodes from soft keyboard (and IME-translated input from hardkeyboard)
         * However, as of Ice Cream Sandwich and later, almost all soft keyboard doesn't generate key presses
         * and so we need to generate them ourselves in commitText.  To avoid duplicates on the handful of keys
         * that still do, we empty this out.
         */

        /*
         * Return DOES still generate a key event, however.  So rather than using it as the 'click a button' key
         * as we do with physical keyboards, let's just use it to hide the keyboard.
         */

//        if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
//            if (SDLActivity.onNativeSoftReturnKey()) {
//                return true;
//            }
//        }
//
//        return super.sendKeyEvent(event);
        // Soft keyboards sometimes deliver a printable KeyEvent instead of commitText().
        // Convert it to text only: do not send a gameplay key-down/up for the same character.
        // Some Android IMEs omit FLAG_SOFT_KEYBOARD but identify their synthetic events
        // with the virtual keyboard device id. Treat those as text, not gameplay key presses.
        boolean softKeyboardEvent = (event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) != 0
                || event.getDeviceId() == KeyCharacterMap.VIRTUAL_KEYBOARD;
        if (softKeyboardEvent && event.isPrintingKey()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && !event.isCtrlPressed() && !event.isAltPressed()) {
                int codePoint = event.getUnicodeChar();
                if (codePoint > 0 && !Character.isISOControl(codePoint)) {
                    commitText(new String(Character.toChars(codePoint)), 1);
                }
            }
            return true;
        }
        if(KeyEvent.ACTION_DOWN == event.getAction()){
            SDLActivity.onNativeKeyDown(event.getKeyCode());
        } else SDLActivity.onNativeKeyUp(event.getKeyCode());
        return true;
    }

    @Override
    public boolean beginBatchEdit() {
        batchEditDepth++;
        return super.beginBatchEdit();
    }

    @Override
    public boolean endBatchEdit() {
        boolean result = super.endBatchEdit();
        if (batchEditDepth > 0) batchEditDepth--;
        if (batchEditDepth == 0) {
            if (textUpdatePending) {
                textUpdatePending = false;
                updateTextNow();
            }
            int backspaces = pendingExternalBackspaces;
            pendingExternalBackspaces = 0;
            dispatchBackspaces(backspaces);
        }
        return result;
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        if (!super.commitText(text, newCursorPosition)) {
            return false;
        }
        updateText();
        return true;
    }

    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        if (!super.setComposingText(text, newCursorPosition)) {
            return false;
        }
        updateText();
        return true;
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        Editable content = getEditable();
        int selectionStart = content == null ? -1 : Selection.getSelectionStart(content);
        int selectionEnd = content == null ? -1 : Selection.getSelectionEnd(content);
        int cursor = selectionStart >= 0 && selectionStart == selectionEnd
                ? selectionStart : (content == null ? 0 : content.length());
        // The game may already have text (notably the initial '/') before this InputConnection
        // was created. Delete locally tracked characters through updateText(), and only send
        // extra backspaces for characters that exist in the game but not in this local buffer.
        int externalBackspaces = beforeLength > 0 && afterLength == 0
                ? Math.max(0, beforeLength - Math.max(0, cursor)) : 0;
        if (!super.deleteSurroundingText(beforeLength, afterLength)) return false;
        updateText();
        queueExternalBackspaces(externalBackspaces);
        return true;
    }

    @Override
    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        Editable content = getEditable();
        int selectionStart = content == null ? -1 : Selection.getSelectionStart(content);
        int selectionEnd = content == null ? -1 : Selection.getSelectionEnd(content);
        int cursor = selectionStart >= 0 && selectionStart == selectionEnd
                ? selectionStart : (content == null ? 0 : content.length());
        String prefix = content == null ? "" : content.subSequence(0, Math.max(0, Math.min(cursor, content.length()))).toString();
        int availableCodePoints = prefix.codePointCount(0, prefix.length());
        int externalBackspaces = beforeLength > 0 && afterLength == 0
                ? Math.max(0, beforeLength - availableCodePoints) : 0;
        if (!super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)) return false;
        updateText();
        queueExternalBackspaces(externalBackspaces);
        return true;
    }

    private void queueExternalBackspaces(int count) {
        if (count <= 0) return;
        if (batchEditDepth > 0) {
            pendingExternalBackspaces += count;
        } else {
            dispatchBackspaces(count);
        }
    }

    private void dispatchBackspaces(int count) {
        if (count <= 0) return;
        boolean sdlRenderActive = SdlBridge.getSdlEnabled() && SdlBridge.isSdlRenderActive();
        for (int i = 0; i < count; i++) {
            if (SDLActivity.dispatchingKeyEvent() || sdlRenderActive) {
                nativeGenerateScancodeForUnichar('\b');
            } else {
                LWJGLCharSender.INSTANCE.sendBackspace();
            }
        }
    }

    protected void updateText() {
        if (batchEditDepth > 0) {
            textUpdatePending = true;
            return;
        }
        updateTextNow();
    }

    private void updateTextNow() {
        final Editable content = getEditable();
        if (content == null) {
            return;
        }

        String text = content.toString();
        // Soft-IME text must be sent to SDL's text-input queue when Minecraft owns SDL rendering.
        // The GLFW character callback remains the correct route for legacy GLFW-rendered versions.
        boolean sdlRenderActive = SdlBridge.getSdlEnabled() && SdlBridge.isSdlRenderActive();
        int compareLength = Math.min(text.length(), mCommittedText.length());
        int matchLength, offset;

        /* Backspace over characters that are no longer in the string */
        for (matchLength = 0; matchLength < compareLength; ) {
            int codePoint = mCommittedText.codePointAt(matchLength);
            if (codePoint != text.codePointAt(matchLength)) {
                break;
            }
            matchLength += Character.charCount(codePoint);
        }
        /* FIXME: This doesn't handle graphemes, like '🌬️' */
        for (offset = matchLength; offset < mCommittedText.length(); ) {
            int codePoint = mCommittedText.codePointAt(offset);
            if (SDLActivity.dispatchingKeyEvent() || sdlRenderActive) {
                // Use SDL's own edit-key path whenever SDL owns the game window.
                nativeGenerateScancodeForUnichar('\b');
            } else {
                LWJGLCharSender.INSTANCE.sendBackspace();
            }
            offset += Character.charCount(codePoint);
        }

        if (matchLength < text.length()) {
            String pendingText = text.subSequence(matchLength, text.length()).toString();
            if (sdlRenderActive) {
                // Match SDL's Android IME contract: commit the complete text to SDL directly.
                // Routing each character through the launcher wrapper can drop input when the
                // launcher/game text-input states change between composing and commit callbacks.
                boolean dispatchingKeyEvent = SDLActivity.dispatchingKeyEvent();
                boolean gameOwnsTextInput = SdlImeController.isGameTextInputActiveForRouting();
                boolean deferSlashText = !gameOwnsTextInput
                        && SdlImeController.shouldDelaySlashText(pendingText);
                for (offset = 0; offset < pendingText.length(); ) {
                    int codePoint = pendingText.codePointAt(offset);
                    if (codePoint == '\n' && !dispatchingKeyEvent
                            && SDLActivity.onNativeSoftReturnKey()) {
                        // Batch IMEs can commit a command and Enter in one update. Preserve
                        // the prefix instead of returning before it ever reaches Minecraft.
                        String prefix = pendingText.substring(0, offset);
                        if (!prefix.isEmpty()) {
                            SdlImeController.commitNativeText(prefix, 0,
                                    deferSlashText && prefix.startsWith("/"));
                        }
                        mCommittedText = text;
                        return;
                    }
                    // Minecraft's active SDL text field consumes SDL text-input events. Sending
                    // a parallel '/' key after the screen is active can toggle the editor or
                    // leave the rendered text and the game's input buffer out of sync.
                    if (!dispatchingKeyEvent && !gameOwnsTextInput && !deferSlashText
                            && codePoint > 0 && codePoint < 128) {
                        nativeGenerateScancodeForUnichar((char) codePoint);
                    }
                    offset += Character.charCount(codePoint);
                }
                SdlImeController.commitNativeText(pendingText, 0, deferSlashText);
            } else if (SDLActivity.dispatchingKeyEvent()) {
                // Preserve SDL's own key/text route for a key event delivered by the active editor.
                for (offset = 0; offset < pendingText.length(); ) {
                    int codePoint = pendingText.codePointAt(offset);
                    if (codePoint == '\n' && SDLActivity.onNativeSoftReturnKey()) {
                        mCommittedText = text;
                        return;
                    }
                    offset += Character.charCount(codePoint);
                }
                SDLInputConnection.nativeCommitText(pendingText, 0);
            } else {
                // Legacy GLFW renderers must use the GLFW character callback instead of SDL events.
                for (offset = 0; offset < pendingText.length(); ) {
                    int codePoint = pendingText.codePointAt(offset);
                    if (codePoint == '\n') {
                        if (!SDLActivity.onNativeSoftReturnKey()) {
                            LWJGLCharSender.INSTANCE.sendEnter();
                        }
                    } else if (Character.isBmpCodePoint(codePoint)) {
                        CallbackBridge.sendCharacterOnly((char) codePoint);
                    } else {
                        // Keep full Unicode for characters outside the Basic Multilingual Plane.
                        SDLInputConnection.nativeCommitText(
                                new String(Character.toChars(codePoint)), 0
                        );
                    }
                    offset += Character.charCount(codePoint);
                }
            }
        }
        mCommittedText = text;
    }

    public static native void nativeCommitText(String text, int newCursorPosition);

    public static native void nativeGenerateScancodeForUnichar(char c);
}

