package com.nepalanurag.lgremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WakeOnLanTest {
    @Test
    public void normalizesCommonMacSpellings() {
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLan.normalize("aabbccddeeff"));
        assertEquals("AA:BB:CC:DD:EE:FF", WakeOnLan.normalize("aa-bb-cc-dd-ee-ff"));
        assertEquals("DC:03:98:51:EF:1F", WakeOnLan.normalize("DC:03:98:51:EF:1F"));
        assertTrue(WakeOnLan.isValidMac("AA-BB-CC-DD-EE-FF"));
    }

    @Test
    public void rejectsBadMacs() {
        assertNull(WakeOnLan.normalize("aa:bb:cc"));
        assertNull(WakeOnLan.normalize("zz:bb:cc:dd:ee:ff"));
        assertNull(WakeOnLan.normalize(null));
        assertFalse(WakeOnLan.isValidMac(""));
    }
}
