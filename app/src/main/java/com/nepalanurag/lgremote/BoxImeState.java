package com.nepalanurag.lgremote;

/**
 * What the box reports about the text field it has focused.
 *
 * <p>The two counters decide whether the box accepts an edit. The live ones arrive in the key
 * inject message. The batch edit the box echoes back carries placeholders that look plausible and
 * are never accepted: echoing those is what makes the box drop typed text without a word.
 *
 * <p>The rule lives in one small class so it can be checked without a box in the room.
 */
final class BoxImeState {
    /** A field edit: the whole content of the field and the caret inside it. */
    static final class Edit {
        final String value;
        final int caret;

        Edit(String value, int caret) {
            this.value = value;
            this.caret = caret;
        }
    }

    private volatile int imeCounter;
    private volatile int fieldCounter;
    private volatile boolean countersKnown;
    private volatile boolean hasField;
    private volatile String value = "";
    private volatile int start;
    private volatile int end;

    /** The box told us which app is in front and which field it has focused. */
    synchronized void onInject(AndroidTvRemote.RemoteInfo info) {
        imeCounter = info.imeCounter;
        countersKnown = true;
        if (info.hasFieldStatus) {
            fieldCounter = info.fieldCounter;
            rememberField(info);
        } else {
            // The inject message without a field status means the box no longer has a text
            // field focused. Do not keep the previous field around for the next edit.
            clearField();
        }
    }

    /** The box asked for the keyboard to be shown for the focused field. */
    synchronized void onShow(AndroidTvRemote.RemoteInfo info) {
        if (info.hasFieldStatus) {
            fieldCounter = info.fieldCounter;
            rememberField(info);
        }
    }

    /** A new box session starts with no field and no known counters. */
    synchronized void reset() {
        imeCounter = 0;
        fieldCounter = 0;
        countersKnown = false;
        clearField();
    }

    /** Records the content we just sent so a second edit builds on it even if no echo arrives. */
    synchronized void applyEdit(Edit edit) {
        value = edit.value == null ? "" : edit.value;
        start = Math.max(0, edit.caret);
        end = start;
        hasField = true;
    }

    /**
     * Applies the backspace we just sent, in case the box does not echo a fresh field status.
     * Without this, the next whole-field edit would put the deleted character back.
     */
    synchronized void applyDelete() {
        if (!hasField) {
            return;
        }
        String current = value == null ? "" : value;
        int from = AndroidTvRemote.codePointOffsetToIndex(current, start);
        int to = AndroidTvRemote.codePointOffsetToIndex(current, end);
        if (from == to) {
            if (from <= 0) {
                return;
            }
            int previous = current.offsetByCodePoints(from, -1);
            value = current.substring(0, previous) + current.substring(from);
            start = end = value.codePointCount(0, previous);
            return;
        }
        if (to < from) {
            int swap = from;
            from = to;
            to = swap;
        }
        value = current.substring(0, from) + current.substring(to);
        start = end = value.codePointCount(0, from);
    }

    /**
     * The box echoed a batch edit. Its counters are placeholders, so they are deliberately
     * ignored: using them is what makes the box drop the next edit.
     */
    synchronized void onBatch(AndroidTvRemote.RemoteInfo info) {
        // Nothing to take from this message on purpose.
    }

    boolean isKnown() {
        return countersKnown;
    }

    boolean hasField() {
        return hasField;
    }

    int imeCounter() {
        return imeCounter;
    }

    int fieldCounter() {
        return fieldCounter;
    }

    /**
     * What the field will contain after inserting {@code text} at the selection the box reported.
     * Only {@code text} goes over the wire; this full value is kept locally so the next edit and
     * any delete build on the box's actual contents.
     */
    synchronized Edit editFor(String text) {
        String current = value == null ? "" : value;
        int from = AndroidTvRemote.codePointOffsetToIndex(current, start);
        int to = AndroidTvRemote.codePointOffsetToIndex(current, end);
        if (to < from) {
            to = from;
        }
        String newValue = current.substring(0, from) + text + current.substring(to);
        int caret = start + text.codePointCount(0, text.length());
        caret = Math.min(caret, newValue.codePointCount(0, newValue.length()));
        return new Edit(newValue, caret);
    }

    private void rememberField(AndroidTvRemote.RemoteInfo info) {
        value = info.fieldValue == null ? "" : info.fieldValue;
        start = Math.max(0, info.fieldStart);
        end = Math.max(0, info.fieldEnd);
        hasField = true;
    }

    private void clearField() {
        value = "";
        start = 0;
        end = 0;
        hasField = false;
    }
}
