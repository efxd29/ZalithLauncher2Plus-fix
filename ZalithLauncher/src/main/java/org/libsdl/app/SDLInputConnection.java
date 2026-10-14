/*
 * This file is part of SDL3 android-project java code.
 * Licensed under the zlib license: https://www.libsdl.org/license.php
 */

package org.libsdl.app;

import android.text.Editable;
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
        // Workaround to capture backspace key. Ref: http://stackoverflow.com/questions>/14560344/android-backspace-in-webview-baseinputconnection
        // and https://bugzilla.libsdl.org/show_bug.cgi?id=2265
        if (beforeLength > 0 && afterLength == 0) {
            // backspace(s)
            while (beforeLength-- > 0) {
                nativeGenerateScancodeForUnichar('\b');
            }
            return true;
       }

        if (!super.deleteSurroundingText(beforeLength, afterLength)) {
            return false;
        }
        updateText();
        return true;
    }

    protected void updateText() {
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
                for (offset = 0; offset < pendingText.length(); ) {
                    int codePoint = pendingText.codePointAt(offset);
                    if (codePoint == '\n' && !dispatchingKeyEvent
                            && SDLActivity.onNativeSoftReturnKey()) {
                        mCommittedText = text;
                        return;
                    }
                    // SDL's Android input contract sends both the printable key event and the
                    // text event for IME input. Mods/config fields often consume key events,
                    // while chat consumes text events. Do not synthesize a second key for
                    // physical keys that are already being dispatched by SDLActivity.
                    if (!dispatchingKeyEvent && codePoint > 0 && codePoint < 128) {
                        nativeGenerateScancodeForUnichar((char) codePoint);
                    }
                    offset += Character.charCount(codePoint);
                }
                SDLInputConnection.nativeCommitText(pendingText, 0);
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

