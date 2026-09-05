package com.xgy.lansms;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure coverage for the persisted cloud-recovery retry decision. */
public class CloudSyncPolicyTest {
    @Test public void transientFailureRequestsJobBackoff() {
        assertTrue(DeviceBackupManager.jobShouldRetry(false, false));
    }

    @Test public void successfulUnregisteredCheckDoesNotRetry() {
        assertFalse(DeviceBackupManager.jobShouldRetry(true, false));
    }

    @Test public void deletedDeviceDoesNotRetryEvenAfterFailure() {
        assertFalse(DeviceBackupManager.jobShouldRetry(false, true));
    }

    @Test public void pendingOutboxKeepsJobOnBackoff() {
        assertTrue(CloudSyncJobService.shouldReschedule(true, 1));
        assertTrue(CloudSyncJobService.shouldReschedule(true, -1));
    }

    @Test public void emptyOutboxAndSuccessfulDeviceSyncCompletesJob() {
        assertFalse(CloudSyncJobService.shouldReschedule(true, 0));
    }
}
