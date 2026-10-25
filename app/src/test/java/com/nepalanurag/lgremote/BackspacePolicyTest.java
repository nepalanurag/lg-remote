package com.nepalanurag.lgremote;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BackspacePolicyTest {
    @Test
    public void emptyFieldForwardsBackspace() {
        assertTrue(BackspacePolicy.shouldForward(true, 0, 0, 0));
    }

    @Test
    public void cursorAtStartWithDraftDoesNotForwardBackspace() {
        assertFalse(BackspacePolicy.shouldForward(true, 0, 0, 4));
    }

    @Test
    public void draftAtCursorEditsLocally() {
        assertFalse(BackspacePolicy.shouldForward(true, 4, 4, 4));
    }

    @Test
    public void selectedTextEditsLocally() {
        assertFalse(BackspacePolicy.shouldForward(true, 1, 3, 4));
    }

    @Test
    public void disabledFieldNeverForwards() {
        assertFalse(BackspacePolicy.shouldForward(false, 0, 0, 0));
    }
}
