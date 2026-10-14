package com.movtery.zalithlauncher.ui.control.input;

import static android.content.Context.INPUT_METHOD_SERVICE;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;

import org.libsdl.app.SDLActivity;
import com.movtery.zalithlauncher.game.sdl.SdlBridge;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * [From PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher/blob/v3_openjdk/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/customcontrols/keyboard/TouchCharInput.java)
 * This class is intended for sending characters used in chat via the virtual keyboard
 */
public class TouchCharInput extends androidx.appcompat.widget.AppCompatEditText {
    public static final String TEXT_FILLER = "                              ";
    public TouchCharInput(@NonNull Context context) {
        this(context, null);
    }
    public TouchCharInput(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, androidx.appcompat.R.attr.editTextStyle);
    }
    public TouchCharInput(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setup();
    }


    private static TouchCharInput sActiveInput;
    private boolean mIsDoingInternalChanges = false;
    private InputListener mListener;

    public void enableKeyboard() {
        // Select the SDL InputConnection from the renderer mode, not from whether a
        // dummy SDL editor has already been created. Otherwise the first tap opens the
        // IME on this EditText and immediately transfers it to SDL, causing a hide/show
        // race and leaving the next InputConnection unable to receive characters.
        if (SdlBridge.sdlEnabled && SdlBridge.isSdlRenderActive()) {
            SDLActivity.enableSDLEditKeyboard();
            return;
        }
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(INPUT_METHOD_SERVICE);
        enable();
        sActiveInput = this;
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
        clear();
    }

    public static void disableActiveInput() {
        if (sActiveInput != null) {
            sActiveInput.disableKeyboard();
        }
    }

    public void disableKeyboard() {
        if (SdlBridge.sdlEnabled && SdlBridge.isSdlRenderActive()) {
            // The SDL-owned dummy editor owns the IME in this mode. Hiding this invisible
            // launcher EditText instead leaves SDL's forced text-input channel stale.
            SDLActivity.disableSDLEditKeyboard();
            clearFocus();
            setVisibility(GONE);
            setEnabled(false);
            if (sActiveInput == this) {
                sActiveInput = null;
            }
            return;
        }
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(getWindowToken(), 0);
        clearFocus();
        setVisibility(GONE);
        setEnabled(false);
        if (sActiveInput == this) {
            sActiveInput = null;
        }
    }

    /**
     * Clear the EditText from any leftover inputs
     * It does not affect the in-game input
     */
    public void clear() {
        mIsDoingInternalChanges = true;
        setText(TEXT_FILLER);
        setSelection(TEXT_FILLER.length());
        mIsDoingInternalChanges = false;
    }

    /** Regain ability to exist, take focus and have some text being input */
    private void enable(){
        setEnabled(true);
        setFocusable(true);
        setVisibility(VISIBLE);
        requestFocus();
    }

    public void setListener(InputListener listener){
        mListener = listener;
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
            disableKeyboard();
        }
        return super.onKeyPreIme(keyCode, event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        // Do not hide the keyboard merely because Android transfers window focus to the
        // IME. Some keyboards briefly report this during their opening animation.
    }

    /** This function deals with anything that has to be executed when the constructor is called */
    private void setup() {
        setOnEditorActionListener((textView, i, keyEvent) -> {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
            if (mListener != null) {
                mListener.onEnter();
            }
            clear();
            return false;
        });
        clear();
        disableKeyboard();
    }

    /**
     * We take the new chars, and send them to the game.
     * If less chars are present, remove some.
     * The text is always cleaned up.
     */
    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        if (mIsDoingInternalChanges)
            return;
        if (mListener != null) {
            for (int i = 0; i < lengthBefore; ++i) {
                mListener.onBackspace();
            }

            for (int i = start, count = 0; count < lengthAfter; ++i) {
                mListener.onSend(text.charAt(i));
                ++count;
            }
        }

        // Reset the keyboard state
        if (text.length() < 1)
            clear();
    }
}
