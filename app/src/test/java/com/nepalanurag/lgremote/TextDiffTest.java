package com.nepalanurag.lgremote;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class TextDiffTest {
    @Test
    public void emptySentTextAppendsWholeDraft() {
        assertEquals(0, TextDiff.commonPrefixLength("abc", ""));
    }

    @Test
    public void sentPrefixOnlyAppendsTheNewTail() {
        assertEquals(2, TextDiff.commonPrefixLength("abc", "ab"));
    }

    @Test
    public void shorterDraftLeavesTheDeletedTailUnmirrored() {
        assertEquals(2, TextDiff.commonPrefixLength("ab", "abc"));
    }

    @Test
    public void replacementKeepsTheSharedPrefix() {
        assertEquals(1, TextDiff.commonPrefixLength("ax", "abc"));
    }

    @Test
    public void identicalTextIsFullyMirrored() {
        assertEquals(3, TextDiff.commonPrefixLength("abc", "abc"));
    }

    @Test
    public void surrogatePairIsNeverSplit() {
        assertEquals(1, TextDiff.commonPrefixLength("a\uD83D\uDE03", "a\uD83D\uDE00"));
    }

    @Test
    public void deletingTheTailNeedsOneDelete() {
        assertEquals(1, TextDiff.deleteCount("ab", "abc"));
    }

    @Test
    public void replacingTheTailNeedsDeletes() {
        assertEquals(2, TextDiff.deleteCount("ax", "abc"));
    }

    @Test
    public void identicalTextNeedsNoDeletes() {
        assertEquals(0, TextDiff.deleteCount("abc", "abc"));
    }

    @Test
    public void emojiCountsAsOneDelete() {
        assertEquals(1, TextDiff.deleteCount("a\uD83D\uDE03", "a\uD83D\uDE00"));
    }

    @Test
    public void appendTextAfterTheSentPrefix() {
        assertEquals("c", TextDiff.appendText("abc", "ab"));
    }

    @Test
    public void appendTextAfterTheSharedPrefix() {
        assertEquals("x", TextDiff.appendText("ax", "abc"));
    }

    @Test
    public void appendTextWhenNothingIsNew() {
        assertEquals("", TextDiff.appendText("abc", "abc"));
    }

    @Test
    public void dropLastCodePointKeepsAsciiIntact() {
        assertEquals("ab", TextDiff.dropLastCodePoint("abc"));
    }

    @Test
    public void dropLastCodePointKeepsEmojiIntact() {
        assertEquals("a", TextDiff.dropLastCodePoint("a\uD83D\uDE03"));
    }

    @Test
    public void dropLastCodePointOnEmptyText() {
        assertEquals("", TextDiff.dropLastCodePoint(""));
    }
}
