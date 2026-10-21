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
}
