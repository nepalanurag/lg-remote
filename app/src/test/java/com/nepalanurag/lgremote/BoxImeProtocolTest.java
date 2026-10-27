package com.nepalanurag.lgremote;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.nepalanurag.lgremote.AndroidTvRemote.Reader;
import com.nepalanurag.lgremote.AndroidTvRemote.RemoteInfo;
import com.nepalanurag.lgremote.AndroidTvRemote.Writer;

import org.junit.Test;

import java.io.IOException;

/**
 * The box text path only works if the bytes on the wire are exactly what the box expects, and the
 * automatic keyboard only opens if the focus detection reads the box messages correctly. Both are
 * checked here without needing the box in the room.
 */
public class BoxImeProtocolTest {

    // ------------------------------------------------------------------ outgoing bytes

    @Test
    public void aKeyPressIsSentAsFieldTenWithTheKeyCode() throws IOException {
        byte[] message = AndroidTvRemote.remoteKey(3); // KEYCODE_HOME
        byte[] key = field(message, 10);
        assertNotNull("the key event must be wrapped in field 10", key);
        assertEquals(3, varint(key, 1));
        assertEquals(3, varint(key, 2)); // short press
    }

    @Test
    public void theKeyCodesUsedByTheRemoteAreTheAndroidOnes() throws IOException {
        assertEquals(3, varint(field(AndroidTvRemote.remoteKey(3), 10), 1));   // Home
        assertEquals(176, varint(field(AndroidTvRemote.remoteKey(176), 10), 1)); // Settings
        assertEquals(67, varint(field(AndroidTvRemote.remoteKey(67), 10), 1));  // Delete
        assertEquals(66, varint(field(AndroidTvRemote.remoteKey(66), 10), 1));  // Enter
    }

    @Test
    public void typedTextIsSentAsFieldTwentyOneWithBothCounters() throws IOException {
        byte[] message = AndroidTvRemote.remoteImeBatchEdit("hi", 2, 7, 11);
        byte[] batch = field(message, 21);
        assertNotNull("the batch edit must be wrapped in field 21", batch);
        assertEquals(7, varint(batch, 1));   // ime counter
        assertEquals(11, varint(batch, 2));  // field counter

        byte[] edit = field(batch, 3);
        assertNotNull("the batch must carry the edit", edit);
        assertEquals(1, varint(edit, 1));

        byte[] textField = field(edit, 2);
        assertNotNull("the edit must carry the text field", textField);
        assertEquals("hi", string(textField, 3));
        assertEquals(2, varint(textField, 1)); // caret
        assertEquals(2, varint(textField, 2)); // caret
    }

    @Test
    public void theCaretCountsCodePointsNotBytes() throws IOException {
        // A single emoji is one code point, so the caret sits at 1 and not at 2.
        byte[] textField = field(field(field(
                AndroidTvRemote.remoteImeBatchEdit("\uD83D\uDE00", 1, 1, 1), 21), 3), 2);
        assertEquals(1, varint(textField, 1));
        assertEquals(1, varint(textField, 2));
        assertEquals("\uD83D\uDE00", string(textField, 3));
    }

    // ------------------------------------------------------------------ counters and edits

    @Test
    public void theLiveCountersComeFromTheKeyInjectMessage() throws IOException {
        BoxImeState state = new BoxImeState();
        state.onInject(AndroidTvRemote.parseRemote(
                imeInject(0, "Browser", "com.internet.tvbrowser",
                        fieldStatus(1, "", 0, 0, "Search"))));
        assertEquals("the ime counter comes from the app info", 0, state.imeCounter());
        assertEquals("the field counter comes from the field status", 1, state.fieldCounter());
    }

    @Test
    public void theEchoedBatchEditCountersAreNotUsed() throws IOException {
        BoxImeState state = new BoxImeState();
        state.onInject(AndroidTvRemote.parseRemote(
                imeInject(0, "Browser", "com.internet.tvbrowser",
                        fieldStatus(1, "", 0, 0, "Search"))));
        // The box echoes the batch edit with placeholder counters. Echoing those back is what
        // makes the box drop the text without a word.
        state.onBatch(AndroidTvRemote.parseRemote(imeBatchEdit(1, 1)));
        assertEquals(0, state.imeCounter());
        assertEquals(1, state.fieldCounter());
    }

    @Test
    public void typingIntoAnEmptyFieldSendsTheWholeValue() throws IOException {
        BoxImeState state = new BoxImeState();
        state.onInject(AndroidTvRemote.parseRemote(
                imeInject(0, "Browser", "com.internet.tvbrowser",
                        fieldStatus(1, "", 0, 0, "Search"))));
        BoxImeState.Edit edit = state.editFor("hello box");
        assertEquals("hello box", edit.value);
        assertEquals(9, edit.caret);
    }

    @Test
    public void typingIntoAFilledFieldKeepsWhatWasThere() throws IOException {
        BoxImeState state = new BoxImeState();
        state.onInject(AndroidTvRemote.parseRemote(
                imeInject(0, "Browser", "com.internet.tvbrowser",
                        fieldStatus(1, "abc", 3, 3, "Search"))));
        BoxImeState.Edit edit = state.editFor("hi");
        assertEquals("abchi", edit.value);
        assertEquals(5, edit.caret);
    }

    @Test
    public void losingTheFieldClearsTheLastValue() throws IOException {
        BoxImeState state = new BoxImeState();
        state.onInject(AndroidTvRemote.parseRemote(
                imeInject(0, "Browser", "com.internet.tvbrowser",
                        fieldStatus(1, "abc", 3, 3, "Search"))));
        assertTrue(state.hasField());
        state.onInject(AndroidTvRemote.parseRemote(
                imeInject(0, "Launcher", "com.nepalanurag.tvhome", null)));
        assertFalse("an inject without a field status means the field is gone", state.hasField());
    }

    @Test
    public void aSentEditBecomesTheBaseForTheNextEdit() throws IOException {
        BoxImeState state = new BoxImeState();
        state.onInject(AndroidTvRemote.parseRemote(
                imeInject(0, "Browser", "com.internet.tvbrowser",
                        fieldStatus(1, "", 0, 0, "Search"))));
        state.applyEdit(state.editFor("hi"));
        BoxImeState.Edit next = state.editFor("!");
        assertEquals("hi!", next.value);
        assertEquals(3, next.caret);
    }

    @Test
    public void aSentDeleteIsAppliedToTheLocalField() throws IOException {
        BoxImeState state = new BoxImeState();
        state.onInject(AndroidTvRemote.parseRemote(
                imeInject(0, "YouTube", "com.google.android.youtube.tv",
                        fieldStatus(1, "ab\uD83D\uDE00", 3, 3, "Search"))));
        state.applyDelete();
        BoxImeState.Edit next = state.editFor("!");
        assertEquals("ab!", next.value);
        assertEquals(3, next.caret);
    }

    @Test
    public void resettingTheSessionForgetsTheOldFieldAndCounters() throws IOException {
        BoxImeState state = new BoxImeState();
        state.onInject(AndroidTvRemote.parseRemote(
                imeInject(7, "Browser", "com.internet.tvbrowser",
                        fieldStatus(4, "abc", 3, 3, "Search"))));
        state.reset();
        assertFalse(state.isKnown());
        assertFalse(state.hasField());
        assertEquals(0, state.imeCounter());
        assertEquals(0, state.fieldCounter());
    }

    @Test
    public void aCaretOffsetCountsCodePoints() {
        assertEquals(0, AndroidTvRemote.codePointOffsetToIndex("", 0));
        assertEquals(1, AndroidTvRemote.codePointOffsetToIndex("a", 1));
        // One emoji is one code point but two bytes in a Java string.
        assertEquals(2, AndroidTvRemote.codePointOffsetToIndex("\uD83D\uDE00", 1));
        assertEquals(0, AndroidTvRemote.codePointOffsetToIndex("\uD83D\uDE00", 0));
    }

    // ------------------------------------------------------------------ incoming bytes

    @Test
    public void aShowRequestMeansAFieldIsFocused() throws IOException {
        RemoteInfo info = AndroidTvRemote.parseRemote(
                imeShow(4, "search", 2, 7, "Search"));
        assertTrue("a show request must be treated as focus", info.imeShow);
        assertTrue(info.hasFieldStatus);
        assertEquals("Search", info.fieldLabel);
        assertEquals("search", info.fieldValue);
        assertEquals(2, info.fieldStart);
        assertEquals(7, info.fieldEnd);
        assertEquals(4, info.fieldCounter);
    }

    @Test
    public void anInjectWithoutFieldStatusMeansNothingIsFocused() throws IOException {
        RemoteInfo info = AndroidTvRemote.parseRemote(imeInject(9, "YouTube", "com.google.android.youtube.tv", null));
        assertTrue(info.imeInject);
        assertFalse("without a field status the field is not focused", info.hasFieldStatus);
        assertEquals("com.google.android.youtube.tv", info.imeApp);
        assertEquals(9, info.imeCounter);
    }

    @Test
    public void anInjectWithFieldStatusCarriesTheFocusedField() throws IOException {
        RemoteInfo info = AndroidTvRemote.parseRemote(imeInject(9, "YouTube", "com.google.android.youtube.tv",
                fieldStatus(5, "cats", 0, 4, "Search")));
        assertTrue(info.imeInject);
        assertTrue(info.hasFieldStatus);
        assertEquals("Search", info.fieldLabel);
        assertEquals("cats", info.fieldValue);
        assertEquals(0, info.fieldStart);
        assertEquals(4, info.fieldEnd);
        assertEquals(5, info.fieldCounter);
        assertTrue(AndroidTvRemote.injectHasFocusedField(info));
    }

    @Test
    public void anEmptyFieldStatusStillMeansTheFieldIsFocused() throws IOException {
        // Empty search fields have no value, label or selection when the cursor first lands.
        RemoteInfo info = AndroidTvRemote.parseRemote(imeInject(0, "YouTube",
                "com.google.android.youtube.tv", fieldStatus(91, "", 0, 0, "")));
        assertTrue(info.hasFieldStatus);
        assertTrue(AndroidTvRemote.injectHasFocusedField(info));
    }

    @Test
    public void anInjectWithoutStatusMeansNoFieldIsFocused() throws IOException {
        RemoteInfo info = AndroidTvRemote.parseRemote(imeInject(9, "YouTube",
                "com.google.android.youtube.tv", null));
        assertFalse(info.hasFieldStatus);
        assertFalse(AndroidTvRemote.injectHasFocusedField(info));
    }

    @Test
    public void aBatchEditMessageRefreshesTheCounters() throws IOException {
        RemoteInfo info = AndroidTvRemote.parseRemote(imeBatchEdit(21, 33));
        assertTrue(info.imeBatchEdit);
        assertEquals(21, info.imeCounter);
        assertEquals(33, info.fieldCounter);
    }

    @Test
    public void aConfigureMessageReportsTheFeatures() throws IOException {
        Writer configure = new Writer();
        configure.varint(1, 615);
        Writer message = new Writer();
        message.bytes(1, configure.bytes());
        RemoteInfo info = AndroidTvRemote.parseRemote(message.bytes());
        assertTrue(info.configure);
        assertEquals(615, info.features);
        // 615 includes the IME feature, which is what makes text entry possible.
        assertTrue((info.features & 4) != 0);
    }

    @Test
    public void voiceBeginIsSentAsFieldThirtyWithSessionId() throws IOException {
        byte[] message = AndroidTvRemote.remoteVoiceBegin(42);
        byte[] voice = field(message, 30);
        assertNotNull("the voice begin must be wrapped in field 30", voice);
        assertEquals(42, varint(voice, 1));
    }

    @Test
    public void voicePayloadIsSentAsFieldThirtyOneWithSessionIdAndSamples() throws IOException {
        byte[] samples = new byte[]{1, 2, 3, 4};
        byte[] message = AndroidTvRemote.remoteVoicePayload(42, samples);
        byte[] payload = field(message, 31);
        assertNotNull("the voice payload must be wrapped in field 31", payload);
        assertEquals(42, varint(payload, 1));
        assertArrayEquals(samples, field(payload, 2));
    }

    @Test
    public void voiceEndIsSentAsFieldThirtyTwoWithSessionId() throws IOException {
        byte[] message = AndroidTvRemote.remoteVoiceEnd(42);
        byte[] voice = field(message, 32);
        assertNotNull("the voice end must be wrapped in field 32", voice);
        assertEquals(42, varint(voice, 1));
    }

    @Test
    public void parseRemoteDecodesVoiceBeginAndEnd() throws IOException {
        Writer beginWriter = new Writer();
        beginWriter.varint(1, 99);
        beginWriter.string(2, "com.google.android.katniss");
        Writer msg1 = new Writer();
        msg1.bytes(30, beginWriter.bytes());

        RemoteInfo beginInfo = AndroidTvRemote.parseRemote(msg1.bytes());
        assertTrue(beginInfo.voiceBegin);
        assertEquals(99, beginInfo.voiceSessionId);
        assertEquals("com.google.android.katniss", beginInfo.voicePackage);

        Writer endWriter = new Writer();
        endWriter.varint(1, 99);
        Writer msg2 = new Writer();
        msg2.bytes(32, endWriter.bytes());

        RemoteInfo endInfo = AndroidTvRemote.parseRemote(msg2.bytes());
        assertTrue(endInfo.voiceEnd);
        assertEquals(99, endInfo.voiceSessionId);
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] field(byte[] data, int wanted) throws IOException {
        Reader reader = new Reader(data);
        while (reader.next()) {
            if (reader.field() == wanted) {
                return reader.bytes();
            }
            reader.skip();
        }
        return null;
    }

    private static long varint(byte[] data, int wanted) throws IOException {
        Reader reader = new Reader(data);
        while (reader.next()) {
            if (reader.field() == wanted) {
                return reader.varint();
            }
            reader.skip();
        }
        fail("missing field " + wanted);
        return 0;
    }

    private static String string(byte[] data, int wanted) throws IOException {
        Reader reader = new Reader(data);
        while (reader.next()) {
            if (reader.field() == wanted) {
                return reader.string();
            }
            reader.skip();
        }
        fail("missing field " + wanted);
        return null;
    }

    private static byte[] fieldStatus(int counter, String value, int start, int end, String label) {
        Writer status = new Writer();
        status.varint(1, counter);
        status.string(2, value);
        status.varint(3, start);
        status.varint(4, end);
        status.string(6, label);
        return status.bytes();
    }

    private static byte[] imeShow(int counter, String value, int start, int end, String label) {
        Writer show = new Writer();
        show.bytes(2, fieldStatus(counter, value, start, end, label));
        Writer message = new Writer();
        message.bytes(22, show.bytes());
        return message.bytes();
    }

    private static byte[] imeInject(int counter, String appLabel, String appId, byte[] status) {
        Writer app = new Writer();
        app.varint(1, counter);
        app.string(10, appLabel);
        app.string(12, appId);
        Writer ime = new Writer();
        ime.bytes(1, app.bytes());
        if (status != null) {
            ime.bytes(2, status);
        }
        Writer message = new Writer();
        message.bytes(20, ime.bytes());
        return message.bytes();
    }

    private static byte[] imeBatchEdit(int imeCounter, int fieldCounter) {
        Writer batch = new Writer();
        batch.varint(1, imeCounter);
        batch.varint(2, fieldCounter);
        Writer message = new Writer();
        message.bytes(21, batch.bytes());
        return message.bytes();
    }
}
