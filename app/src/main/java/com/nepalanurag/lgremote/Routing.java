package com.nepalanurag.lgremote;

/**
 * The one rule that decides which device a remote button talks to.
 *
 * <p>Keeping it as plain data means the routing can be checked without a TV or a box in the room:
 * the unit tests in {@code RoutingTest} cover the same decisions the UI relies on.
 */
public final class Routing {

    private Routing() {
    }

    /**
     * True when the input currently on screen is the one that was linked to the Android TV box.
     *
     * <p>The link is stored as the input id the TV reported (for example {@code HDMI_3}). Some
     * firmware reports the same input under a different id for the app that owns it, so the
     * configured id is matched against both the id and the app id of the active input.
     *
     * @param configuredInputId the input id linked to the box, may be null or empty
     * @param activeInputId     the id of the input on screen, may be null
     * @param activeAppId       the app id of the input on screen, may be null
     */
    public static boolean isBoxInput(String configuredInputId, String activeInputId,
                                     String activeAppId) {
        if (configuredInputId == null || configuredInputId.isEmpty()) {
            return false;
        }
        return configuredInputId.equals(activeInputId) || configuredInputId.equals(activeAppId);
    }
}
