package com.xgy.lansms;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class DeviceBackupTest {
    @Test public void aadBindsBackupIdRevisionAndSalt() {
        String first = DeviceBackupManager.aad("backup-a", 1L, "salt-a");
        assertNotEquals(first, DeviceBackupManager.aad("backup-a", 2L, "salt-a"));
        assertNotEquals(first, DeviceBackupManager.aad("backup-b", 1L, "salt-a"));
        assertNotEquals(first, DeviceBackupManager.aad("backup-a", 1L, "salt-b"));
    }

    @Test public void backupEncryptionFailsWhenAadChanges() throws Exception {
        DeviceIdentity identity = DeviceIdentity.derive("android-id", "cert", "com.xgy.lansms");
        byte[] salt = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] key = identity.deriveBackupKey(salt);
        CloudCrypto.Encrypted encrypted = CloudCrypto.encrypt(key, "links".getBytes(StandardCharsets.UTF_8),
                DeviceBackupManager.aad(identity.backupId, 1L, CloudCrypto.b64(salt)).getBytes(StandardCharsets.UTF_8));
        byte[] decoded = CloudCrypto.decrypt(key, encrypted.nonce, encrypted.ciphertext,
                DeviceBackupManager.aad(identity.backupId, 1L, CloudCrypto.b64(salt)).getBytes(StandardCharsets.UTF_8));
        assertEquals("links", new String(decoded, StandardCharsets.UTF_8));
        try {
            CloudCrypto.decrypt(key, encrypted.nonce, encrypted.ciphertext,
                    DeviceBackupManager.aad(identity.backupId, 2L, CloudCrypto.b64(salt)).getBytes(StandardCharsets.UTF_8));
            fail("tampered revision must fail GCM authentication");
        } catch (Exception expected) { }
    }
}
