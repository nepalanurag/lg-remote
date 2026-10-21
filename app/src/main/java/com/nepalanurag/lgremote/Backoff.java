package com.nepalanurag.lgremote;

/** Reconnect delays in milliseconds, with a little jitter so two phones don't sync up. */
public final class Backoff {
    private static final int[] DELAYS = {1000, 2000, 4000, 8000, 10000, 15000, 30000};

    private Backoff() {
    }

    public static int baseDelay(int attempt) {
        int index = Math.max(0, Math.min(DELAYS.length - 1, attempt));
        return DELAYS[index];
    }

    public static int delay(int attempt, int jitter) {
        return baseDelay(attempt) + Math.max(0, Math.min(500, jitter));
    }
}
