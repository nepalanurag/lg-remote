package com.nepalanurag.lgremote;

import android.content.Context;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;

/**
 * Text field for the phone keyboard. A backspace with an empty draft is forwarded to the active
 * screen instead of disappearing into an empty EditText.
 */
final class RemoteEditText extends EditText {
    interface BackspaceListener {
        void onBackspace();
    }

    private BackspaceListener backspaceListener;

    RemoteEditText(Context context) {
        super(context);
    }

    void setBackspaceListener(BackspaceListener listener) {
        backspaceListener = listener;
    }

    private boolean hasNothingLocalToDelete() {
        return BackspacePolicy.shouldForward(isEnabled(), getSelectionStart(), getSelectionEnd(),
                length());
    }

    private boolean forwardBackspace() {
        if (backspaceListener == null || !hasNothingLocalToDelete()) {
            return false;
        }
        backspaceListener.onBackspace();
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        InputConnection base = super.onCreateInputConnection(outAttrs);
        if (base == null) {
            return null;
        }
        return new InputConnectionWrapper(base, true) {
            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (beforeLength > 0 && afterLength == 0 && forwardBackspace()) {
                    return true;
                }
                return super.deleteSurroundingText(beforeLength, afterLength);
            }

            @Override
            public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
                if (beforeLength > 0 && afterLength == 0 && forwardBackspace()) {
                    return true;
                }
                return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_DEL) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && forwardBackspace()) {
                        return true;
                    }
                    if (event.getAction() == KeyEvent.ACTION_UP && hasNothingLocalToDelete()) {
                        return true;
                    }
                }
                return super.sendKeyEvent(event);
            }
        };
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DEL && forwardBackspace()) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DEL && hasNothingLocalToDelete()) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
}
