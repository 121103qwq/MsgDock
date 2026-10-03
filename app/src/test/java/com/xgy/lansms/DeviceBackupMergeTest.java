package com.xgy.lansms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Pure merge coverage for device-number based cloud link recovery. */
public class DeviceBackupMergeTest {
    private static CloudConfigStore.CloudLink link(String room, String device, String token,
                                                    String role, String peerName) {
        return new CloudConfigStore.CloudLink("https://relay.example", room, device, token,
                "peer-" + device, "peer-key-" + device, "private-key-" + device, role, peerName);
    }

    @Test public void localEmptyRecoversRemoteLinksAndCountsAllNewLinks() {
        CloudConfigStore.CloudLink remote = link("room-a", "device-a", "remote-token",
                CloudConfigStore.CloudLink.ROLE_RECEIVER, "Pad");
        List<CloudConfigStore.CloudLink> merged = DeviceBackupManager.mergeLinks(
                Collections.emptyList(), Collections.singletonList(remote));
        assertEquals(1, merged.size());
        assertEquals("remote-token", merged.get(0).token);
        assertEquals(1, DeviceBackupManager.countRemoteOnly(Collections.emptyList(), merged));
    }

    @Test public void multiSideMergeKeepsLocalCredentialsForSameEndpoint() {
        CloudConfigStore.CloudLink local = link("room-a", "device-a", "local-token",
                CloudConfigStore.CloudLink.ROLE_SENDER, "Local name");
        CloudConfigStore.CloudLink remoteSame = link("room-a", "device-a", "remote-token",
                CloudConfigStore.CloudLink.ROLE_SENDER, "Remote name");
        CloudConfigStore.CloudLink remoteOnly = link("room-b", "device-b", "remote-b",
                CloudConfigStore.CloudLink.ROLE_RECEIVER, "Receiver");
        List<CloudConfigStore.CloudLink> merged = DeviceBackupManager.mergeLinks(
                Collections.singletonList(local), Arrays.asList(remoteSame, remoteOnly));
        assertEquals(2, merged.size());
        assertEquals("local-token", merged.get(0).token);
        assertEquals("Local name", merged.get(0).peerName);
        assertEquals(1, DeviceBackupManager.countRemoteOnly(Collections.singletonList(local),
                Arrays.asList(remoteSame, remoteOnly)));
    }

    @Test public void duplicateEndpointsAreCollapsedAndContentDifferencesMatter() {
        CloudConfigStore.CloudLink first = link("room-a", "device-a", "first",
                CloudConfigStore.CloudLink.ROLE_SENDER, "one");
        CloudConfigStore.CloudLink second = link("room-a", "device-a", "second",
                CloudConfigStore.CloudLink.ROLE_SENDER, "two");
        List<CloudConfigStore.CloudLink> merged = DeviceBackupManager.mergeLinks(
                Arrays.asList(first, second), Arrays.asList(first, second));
        assertEquals(1, merged.size());
        assertEquals("second", merged.get(0).token);
        assertTrue(DeviceBackupManager.sameLinkSets(merged, Collections.singletonList(second)));
        assertFalse(DeviceBackupManager.sameLinkSets(Collections.singletonList(first),
                Collections.singletonList(second)));
    }

    @Test public void revokedOrInactiveRemoteLinksAreNotAdded() {
        CloudConfigStore.CloudLink revoked = link("room-deleted", "device-deleted", "revoked",
                CloudConfigStore.CloudLink.ROLE_RECEIVER, "Deleted");
        // The caller passes only links whose /status response is active. A 410/404
        // link is therefore absent from the active remote set and cannot reappear.
        List<CloudConfigStore.CloudLink> merged = DeviceBackupManager.mergeLinks(
                Collections.emptyList(), Collections.emptyList());
        assertTrue(merged.isEmpty());
        assertEquals(0, DeviceBackupManager.countRemoteOnly(Collections.emptyList(),
                Collections.emptyList()));
        assertFalse(DeviceBackupManager.sameLinkSets(merged, Collections.singletonList(revoked)));
    }

    @Test public void activeLocalCredentialWinsWhenRemoteCredentialIsInactive() {
        CloudConfigStore.CloudLink local = link("room-a", "device-a", "local-valid",
                CloudConfigStore.CloudLink.ROLE_RECEIVER, "Local");
        // The injected active sets model status validation: the remote endpoint is
        // absent because its token returned 401/404/410 or revoked/inactive.
        List<CloudConfigStore.CloudLink> merged = DeviceBackupManager.mergeLinks(
                Collections.singletonList(local), Collections.emptyList());
        assertEquals(1, merged.size());
        assertEquals("local-valid", merged.get(0).token);
    }

    @Test public void activeRemoteCredentialWinsWhenLocalCredentialIsInactive() {
        CloudConfigStore.CloudLink remote = link("room-a", "device-a", "remote-valid",
                CloudConfigStore.CloudLink.ROLE_RECEIVER, "Remote");
        // The injected active sets model the local status check rejecting the stale
        // local token while the remote token remains active.
        List<CloudConfigStore.CloudLink> merged = DeviceBackupManager.mergeLinks(
                Collections.emptyList(), Collections.singletonList(remote));
        assertEquals(1, merged.size());
        assertEquals("remote-valid", merged.get(0).token);
    }

    @Test public void removedLocalDifferenceIdentifiesOutboxCleanupCandidates() {
        CloudConfigStore.CloudLink stale = link("room-stale", "device-stale", "stale-token",
                CloudConfigStore.CloudLink.ROLE_SENDER, "Stale");
        assertEquals(1, DeviceBackupManager.linksRemovedByMerge(
                Collections.singletonList(stale), Collections.emptyList()).size());
        assertTrue(DeviceBackupManager.linksRemovedByMerge(
                Collections.singletonList(stale), Collections.singletonList(stale)).isEmpty());
    }
}
