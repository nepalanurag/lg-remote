package com.nepalanurag.lgremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

public class ProtocolTest {
    private static final String PAIRING = "{\"forcePairing\":false,\"pairingType\":\"PROMPT\","
            + "\"manifest\":{\"manifestVersion\":1,\"permissions\":[\"CONTROL_AUDIO\",\"LAUNCH\"]}}";

    @Test
    public void registerIncludesTheSavedKeyAndTheManifest() {
        String json = Protocol.registerPayload(PAIRING, "secret-key", "register_1");
        JsonObject message = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("register", message.get("type").getAsString());
        assertEquals("register_1", message.get("id").getAsString());
        JsonObject payload = message.getAsJsonObject("payload");
        assertEquals("secret-key", payload.get("client-key").getAsString());
        assertEquals("PROMPT", payload.get("pairingType").getAsString());
        assertEquals(1, payload.getAsJsonObject("manifest").get("manifestVersion").getAsInt());
    }

    @Test
    public void registerNeverInventsAKey() {
        String json = Protocol.registerPayload(PAIRING, null, "register_2");
        JsonObject payload = JsonParser.parseString(json).getAsJsonObject()
                .getAsJsonObject("payload");
        assertFalse(payload.has("client-key"));
    }

    @Test
    public void freshPairingCanRequestPinMode() {
        String json = Protocol.registerPayload(PAIRING, null, "PIN", "register_3");
        JsonObject payload = JsonParser.parseString(json).getAsJsonObject()
                .getAsJsonObject("payload");
        assertEquals("PIN", payload.get("pairingType").getAsString());
        assertFalse(payload.has("client-key"));
    }

    @Test
    public void savedKeyRegistrationKeepsManifestMode() {
        String json = Protocol.registerPayload(PAIRING, "secret-key", null, "register_4");
        JsonObject payload = JsonParser.parseString(json).getAsJsonObject()
                .getAsJsonObject("payload");
        assertEquals("PROMPT", payload.get("pairingType").getAsString());
        assertEquals("secret-key", payload.get("client-key").getAsString());
    }

    @Test
    public void pairingPinAcceptsOnlyFourToEightDigits() {
        assertTrue(Protocol.isValidPairingPin("1234"));
        assertTrue(Protocol.isValidPairingPin("123456"));
        assertTrue(Protocol.isValidPairingPin("12345678"));
        assertFalse(Protocol.isValidPairingPin(null));
        assertFalse(Protocol.isValidPairingPin(""));
        assertFalse(Protocol.isValidPairingPin("123"));
        assertFalse(Protocol.isValidPairingPin("123456789"));
        assertFalse(Protocol.isValidPairingPin("12 34"));
        assertFalse(Protocol.isValidPairingPin("1234a"));
    }

    @Test
    public void pairingPinRequestUsesWebOsSetPinUri() {
        JsonObject message = JsonParser.parseString(
                Protocol.setPairingPin("pin_1", " 12345678 ")).getAsJsonObject();
        assertEquals("request", message.get("type").getAsString());
        assertEquals("pin_1", message.get("id").getAsString());
        assertEquals("ssap://pairing/setPin", message.get("uri").getAsString());
        assertEquals("12345678",
                message.getAsJsonObject("payload").get("pin").getAsString());
    }

    @Test
    public void pairingRequestsMayRunBeforeRegistrationButNormalOnesMayNot() {
        assertTrue(LgTvClient.canSendRequest(false, true, true));
        assertTrue(LgTvClient.canSendRequest(true, true, false));
        assertFalse(LgTvClient.canSendRequest(false, true, false));
        assertFalse(LgTvClient.canSendRequest(false, false, true));
        assertFalse(LgTvClient.canSendRequest(true, false, false));
    }

    @Test
    public void requestHasTypeIdUriAndPayload() {
        JsonObject volume = new JsonObject();
        volume.addProperty("volume", 18);
        JsonObject message = JsonParser.parseString(
                Protocol.request("req_1", "ssap://audio/setVolume", volume)).getAsJsonObject();
        assertEquals("request", message.get("type").getAsString());
        assertEquals("req_1", message.get("id").getAsString());
        assertEquals("ssap://audio/setVolume", message.get("uri").getAsString());
        assertEquals(18, message.getAsJsonObject("payload").get("volume").getAsInt());
    }

    @Test
    public void subscribeIsItsOwnType() {
        JsonObject message = JsonParser.parseString(
                Protocol.subscribe("volume", "ssap://audio/getVolume", new JsonObject()))
                .getAsJsonObject();
        assertEquals("subscribe", message.get("type").getAsString());
        assertEquals("volume", message.get("id").getAsString());
    }

    @Test
    public void pointerButtonUsesNewlineDelimitedFormat() {
        assertEquals("type:button\nname:HOME\n\n", Protocol.pointerButton("HOME"));
        assertEquals("type:button\nname:VOLUMEUP\n\n", Protocol.pointerButton("VOLUMEUP"));
        assertThrows(IllegalArgumentException.class, () -> Protocol.pointerButton("volumeup"));
        assertThrows(IllegalArgumentException.class,
                () -> Protocol.pointerButton("HOME\nname:BACK"));
    }

    @Test
    public void pointerMoveClampsAndWritesButtonState() {
        assertEquals("type:move\ndx:10\ndy:-5\ndown:0\n\n",
                Protocol.pointerMove(10, -5, false));
        assertEquals("type:move\ndx:10\ndy:5\ndown:1\n\n",
                Protocol.pointerMove(10, 5, true));
        assertTrue(Protocol.pointerMove(99999, -99999, false)
                .contains("dx:2000\ndy:-2000"));
    }

    @Test
    public void clickAndScrollFormats() {
        assertEquals("type:click\n\n", Protocol.pointerClick());
        assertEquals("type:scroll\ndx:0\ndy:7\n\n", Protocol.pointerScroll(0, 7));
    }

    @Test
    public void textPayloadsTargetTheOpenTvField() {
        JsonObject insert = Protocol.insertTextPayload("hello");
        assertEquals("hello", insert.get("text").getAsString());
        assertFalse(insert.get("replace").getAsBoolean());
        assertEquals(1, Protocol.deleteCharactersPayload(0).get("count").getAsInt());
        assertEquals(100, Protocol.deleteCharactersPayload(999).get("count").getAsInt());
        assertEquals(3, Protocol.deleteCharactersPayload(3).get("count").getAsInt());
    }
}
