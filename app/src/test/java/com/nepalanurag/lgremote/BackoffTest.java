package com.nepalanurag.lgremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BackoffTest {
    @Test
    public void delaysGrowAndThenCap() {
        assertEquals(1000, Backoff.baseDelay(0));
        assertEquals(2000, Backoff.baseDelay(1));
        assertEquals(4000, Backoff.baseDelay(2));
        assertEquals(8000, Backoff.baseDelay(3));
        assertEquals(10000, Backoff.baseDelay(4));
        assertEquals(30000, Backoff.baseDelay(20));
        assertEquals(1000, Backoff.baseDelay(-5));
    }

    @Test
    public void jitterIsSmallAndNonNegative() {
        for (int i = 0; i < 100; i++) {
            int delay = Backoff.delay(2, i * 7);
            assertTrue(delay >= 4000);
            assertTrue(delay <= 4500);
        }
        assertEquals(4000, Backoff.delay(2, -10));
        assertEquals(4500, Backoff.delay(2, 5000));
    }
}
