package com.nepalanurag.lgremote;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Builds the newline/JSON messages understood by LG's SSAP endpoint. */
public final class Protocol {

    public static final String POINTER_SOCKET =
            "ssap://com.webos.service.networkinput/getPointerInputSocket";
    public static final String SET_PAIRING_PIN = "ssap://pairing/setPin";

    /** Button names accepted by the webOS pointer socket. */
    public static final Set<String> BUTTONS = new HashSet<>(Arrays.asList(
            "LEFT", "RIGHT", "UP", "DOWN", "ENTER", "BACK", "EXIT", "HOME", "MENU", "INFO",
            "DASH", "ASTERISK", "CC", "PLAY", "PAUSE", "STOP", "REWIND", "FASTFORWARD",
            "RED", "GREEN", "YELLOW", "BLUE", "VOLUMEUP", "VOLUMEDOWN", "MUTE",
            "CHANNELUP", "CHANNELDOWN",
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9"));

    private Protocol() {
    }

    /** The register payload: lgtv2's pairing manifest plus the saved client key, when present. */
    public static String registerPayload(String pairingJson, String clientKey, String id) {
        return registerPayload(pairingJson, clientKey, null, id);
    }

    /**
     * The register payload with an optional pairing mode. PIN makes the TV show a code on
     * screen that the user enters on the phone, so no TV remote is needed for the first pair.
     */
    public static String registerPayload(String pairingJson, String clientKey,
                                         String pairingType, String id) {
        JsonObject payload = JsonParser.parseString(pairingJson).getAsJsonObject();
        if (clientKey != null && !clientKey.isEmpty()) {
            payload.addProperty("client-key", clientKey);
        }
        if (pairingType != null && !pairingType.isEmpty()) {
            payload.addProperty("pairingType", pairingType);
        }
        JsonObject out = new JsonObject();
        out.addProperty("type", "register");
        if (id != null) {
            out.addProperty("id", id);
        }
        out.add("payload", payload);
        return out.toString();
    }

    /** A PIN is numeric and LG has used both 6- and 8-digit codes on webOS. */
    public static boolean isValidPairingPin(String pin) {
        return pin != null && pin.trim().matches("\\d{4,8}");
    }

    /** Sends the code shown on the TV back to webOS to finish the pairing handshake. */
    public static String setPairingPin(String id, String pin) {
        JsonObject payload = new JsonObject();
        payload.addProperty("pin", pin == null ? "" : pin.trim());
        return request(id, SET_PAIRING_PIN, payload);
    }

    public static String request(String id, String uri, JsonObject payload) {
        return message("request", id, uri, payload);
    }

    public static String subscribe(String id, String uri, JsonObject payload) {
        return message("subscribe", id, uri, payload);
    }

    private static String message(String type, String id, JsonObject payload) {
        return message(type, id, null, payload);
    }

    private static String message(String type, String id, String uri, JsonObject payload) {
        JsonObject out = new JsonObject();
        out.addProperty("type", type);
        if (id != null) {
            out.addProperty("id", id);
        }
        if (uri != null) {
            out.addProperty("uri", uri);
        }
        out.add("payload", payload == null ? new JsonObject() : payload);
        return out.toString();
    }

    public static String pointerButton(String name) {
        if (name == null || !BUTTONS.contains(name)) {
            throw new IllegalArgumentException("Unknown TV button: " + name);
        }
        return "type:button\nname:" + name + "\n\n";
    }

    public static String pointerClick() {
        return "type:click\n\n";
    }

    public static String pointerMove(int dx, int dy, boolean buttonDown) {
        return "type:move\ndx:" + clamp(dx) + "\ndy:" + clamp(dy)
                + "\ndown:" + (buttonDown ? 1 : 0) + "\n\n";
    }

    public static String pointerScroll(int dx, int dy) {
        return "type:scroll\ndx:" + clamp(dx) + "\ndy:" + clamp(dy) + "\n\n";
    }

    /** Payload for ssap://com.webos.service.ime/insertText. */
    public static JsonObject insertTextPayload(String text) {
        JsonObject payload = new JsonObject();
        payload.addProperty("text", text == null ? "" : text);
        payload.addProperty("replace", false);
        return payload;
    }

    /** Payload for ssap://com.webos.service.ime/deleteCharacters. */
    public static JsonObject deleteCharactersPayload(int count) {
        JsonObject payload = new JsonObject();
        payload.addProperty("count", Math.max(1, Math.min(100, count)));
        return payload;
    }

    private static int clamp(int v) {
        return Math.max(-2000, Math.min(2000, v));
    }
}
