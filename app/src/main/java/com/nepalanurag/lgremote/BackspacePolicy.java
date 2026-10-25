package com.nepalanurag.lgremote;

/** Decides whether a backspace edits the local draft or goes to the active screen. */
final class BackspacePolicy {
    private BackspacePolicy() {
    }

    /**
     * A backspace goes to the device only when the phone draft is empty. Once the draft has
     * characters, the normal text field edit wins and the mirrored delete follows it; sending
     * both would remove one character on the phone and another one on the TV or box.
     */
    static boolean shouldForward(boolean enabled, int selectionStart, int selectionEnd,
                                 int textLength) {
        return enabled && textLength == 0;
    }
}
