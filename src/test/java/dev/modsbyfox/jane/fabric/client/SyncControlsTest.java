package dev.modsbyfox.jane.fabric.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SyncControlsTest {
    @Test void runningTransferShowsOnlyCancelInThePrimaryButtonArea() {
        SyncControls running = SyncControls.select(true, false, false, false,
                true, true, true, true, true);
        assertFalse(running.trusted());
        assertFalse(running.serverRoute());
        assertFalse(running.serverOnly());
        assertFalse(running.retryLookup());
        assertTrue(running.cancelTransfer());
        assertFalse(running.finish());

        SyncControls idle = SyncControls.select(false, false, false, false,
                true, true, false, false, true);
        assertTrue(idle.trusted());
        assertTrue(idle.serverRoute());
        assertFalse(idle.cancelTransfer());
    }
}
