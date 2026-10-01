package com.xgy.lansms;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Process-local delivery claims prevent LAN/cloud double notifications. */
public class DeliveryClaimTest {
    @Test public void claimIsExclusiveUntilReleased() {
        String id = "claim-test-id";
        CloudInboxStore.release(id);
        assertTrue(CloudInboxStore.claim(id));
        assertFalse(CloudInboxStore.claim(id));
        CloudInboxStore.release(id);
        assertTrue(CloudInboxStore.claim(id));
        CloudInboxStore.release(id);
    }
}
