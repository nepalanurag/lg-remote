package com.nepalanurag.lgremote;

/** Helpers for mirroring a phone draft to the active screen one edit at a time. */
public final class TextDiff {
    private TextDiff() {
    }

    /**
     * Length of the part of the draft that is already mirrored. The result never splits a
     * surrogate pair, so deleting the unmirrored tail never damages an emoji character.
     */
    public static int commonPrefixLength(String current, String sent) {
        if (current == null || sent == null) {
            return 0;
        }
        int max = Math.min(current.length(), sent.length());
        int index = 0;
        while (index < max && current.charAt(index) == sent.charAt(index)) {
            index++;
        }
        if (index > 0 && index < current.length() && index < sent.length()
                && Character.isHighSurrogate(current.charAt(index - 1))
                && Character.isLowSurrogate(current.charAt(index))) {
            index--;
        }
        return index;
    }

    /** How many device deletions are needed before the new tail can be appended. */
    public static int deleteCount(String current, String sent) {
        if (sent == null || sent.isEmpty()) {
            return 0;
        }
        int prefix = commonPrefixLength(current, sent);
        return sent.codePointCount(prefix, sent.length());
    }

    /** The part of the draft that is not on the device yet. */
    public static String appendText(String current, String sent) {
        if (current == null) {
            return "";
        }
        String safeSent = sent == null ? "" : sent;
        return current.substring(commonPrefixLength(current, safeSent));
    }

    /** Removes one complete character, keeping surrogate pairs (emoji) intact. */
    public static String dropLastCodePoint(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.substring(0, text.offsetByCodePoints(text.length(), -1));
    }
}
