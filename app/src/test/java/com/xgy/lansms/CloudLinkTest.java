package com.xgy.lansms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import java.util.Arrays;

public class CloudLinkTest {
    @Test public void linksKeepSenderAndReceiverRoles() {
        CloudConfigStore.CloudLink sender = new CloudConfigStore.CloudLink(
                "https://relay.example", "room-a", "phone-a", "token-a", "win-a", "peer-a", "private-a", "sender");
        CloudConfigStore.CloudLink receiver = new CloudConfigStore.CloudLink(
                "https://relay.example", "room-b", "pad-b", "token-b", "phone-b", "peer-b", "private-b", "receiver");
        CloudConfigStore.Config config = new CloudConfigStore.Config("https://relay.example", Arrays.asList(sender, receiver));
        assertEquals(2, config.links.size());
        assertTrue(config.links.get(0).isSender());
        assertTrue(config.links.get(1).isReceiver());
        assertTrue(CloudConfigStore.isConfigured(sender));
        assertTrue(CloudConfigStore.isConfigured(receiver));
    }

    @Test public void relayUrlRejectsCleartextAndCredentials() {
        assertTrue(CloudConfigStore.isSecureRelayUrl("https://relay.example/path"));
        assertTrue(!CloudConfigStore.isSecureRelayUrl("http://relay.example"));
        assertTrue(!CloudConfigStore.isSecureRelayUrl("https://user:pass@relay.example"));
    }

    @Test public void peerNameIsOptionalForOldConstructorAndPreservedForNewConstructor() {
        CloudConfigStore.CloudLink old = new CloudConfigStore.CloudLink("https://relay.example", "room", "device", "token",
                "peer", "key", "private", "sender");
        assertEquals("", old.peerName);
        CloudConfigStore.CloudLink named = new CloudConfigStore.CloudLink("https://relay.example", "room", "device", "token",
                "peer", "key", "private", "sender", "Windows PC");
        assertEquals("Windows PC", named.peerName);
        assertEquals("device", named.deviceId);
        assertEquals("private", named.privateKey);
    }
}
