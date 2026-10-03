package com.xgy.lansms;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReceiverLifecyclePolicyTest {
    @Test public void explicitStopIsTheOnlyNonStickyCommand() {
        assertTrue(ReceiverLifecyclePolicy.isExplicitStop(ReceiverService.ACTION_STOP));
        assertFalse(ReceiverLifecyclePolicy.shouldPersistEnabled(ReceiverService.ACTION_STOP));
        assertFalse(ReceiverLifecyclePolicy.shouldStaySticky(ReceiverService.ACTION_STOP));
    }

    @Test public void normalAndNullStartsRemainSticky() {
        assertTrue(ReceiverLifecyclePolicy.shouldPersistEnabled("normal-start"));
        assertTrue(ReceiverLifecyclePolicy.shouldStaySticky("normal-start"));
        // null is Android's START_STICKY restart intent and must preserve the preference.
        assertFalse(ReceiverLifecyclePolicy.shouldPersistEnabled(null));
        assertTrue(ReceiverLifecyclePolicy.shouldStaySticky(null));
    }
}
