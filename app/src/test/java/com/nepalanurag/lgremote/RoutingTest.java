package com.nepalanurag.lgremote;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The routing rule decides which device a button talks to. These tests pin it down so a change
 * cannot silently send D-pad presses to the TV while the box input is on screen.
 */
public class RoutingTest {

    @Test
    public void theLinkedInputSendsButtonsToTheBox() {
        assertTrue(Routing.isBoxInput("HDMI_3", "HDMI_3", null));
    }

    @Test
    public void aDifferentInputKeepsTheTvInCharge() {
        assertFalse(Routing.isBoxInput("HDMI_3", "HDMI_2", null));
        assertFalse(Routing.isBoxInput("HDMI_3", null, null));
    }

    @Test
    public void theFirmwareAppIdAlsoMatchesTheLinkedInput() {
        // Some firmware reports the same physical input under an app id.
        assertTrue(Routing.isBoxInput("HDMI_3", null, "HDMI_3"));
        assertTrue(Routing.isBoxInput("com.webos.app.livetv", "com.webos.app.livetv", null));
    }

    @Test
    public void anUnlinkedBoxFallsBackToTheTv() {
        assertFalse(Routing.isBoxInput("", "HDMI_3", null));
        assertFalse(Routing.isBoxInput(null, "HDMI_3", null));
    }

    @Test
    public void aNullActiveInputNeverMatches() {
        // No input on screen means a webOS app is in front, so the TV stays in charge.
        assertFalse(Routing.isBoxInput("HDMI_3", null, null));
    }

    @Test
    public void androidTvOnlyModeAlwaysRoutesToTheBox() {
        assertTrue(Routing.isBoxActive(SecureStore.MODE_ANDROID_TV, null, null, null));
        assertTrue(Routing.isBoxActive(SecureStore.MODE_ANDROID_TV, "HDMI_1", "HDMI_2", null));
    }

    @Test
    public void lgTvOnlyModeAlwaysRoutesToTheTv() {
        assertFalse(Routing.isBoxActive(SecureStore.MODE_LG_TV, "HDMI_1", "HDMI_1", null));
    }

    @Test
    public void autoModeDelegatesToActiveInput() {
        assertTrue(Routing.isBoxActive(SecureStore.MODE_AUTO, "HDMI_3", "HDMI_3", null));
        assertFalse(Routing.isBoxActive(SecureStore.MODE_AUTO, "HDMI_3", "HDMI_1", null));
    }
}
