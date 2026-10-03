package com.xgy.lansms;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

public class DeviceIdentityTest {
    @Test public void sameInputsProduceStableIdentity() throws Exception {
        DeviceIdentity first = DeviceIdentity.derive("android-id-a", "cert-a", "com.xgy.lansms");
        DeviceIdentity second = DeviceIdentity.derive("android-id-a", "cert-a", "com.xgy.lansms");
        assertEquals(first.backupId, second.backupId);
        assertEquals(first.machineCode, second.machineCode);
        assertEquals(first.backupToken, second.backupToken);
        assertArrayEquals(first.backupKey, second.backupKey);
        assertTrue(first.backupId.startsWith("backup_"));
        assertTrue(first.machineCode.matches("XGY-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}"));
    }

    @Test public void identityInputsAreIsolated() throws Exception {
        DeviceIdentity base = DeviceIdentity.derive("android-id-a", "cert-a", "com.xgy.lansms");
        DeviceIdentity changedId = DeviceIdentity.derive("android-id-b", "cert-a", "com.xgy.lansms");
        DeviceIdentity changedCert = DeviceIdentity.derive("android-id-a", "cert-b", "com.xgy.lansms");
        assertNotEquals(base.backupId, changedId.backupId);
        assertNotEquals(base.backupId, changedCert.backupId);
        assertFalse(Arrays.equals(base.backupKey, changedId.backupKey));
        assertFalse(base.backupToken.equals(changedCert.backupToken));
    }

    @Test public void perBackupKeyAndMachineCodeDoNotExposeInputs() throws Exception {
        DeviceIdentity identity = DeviceIdentity.derive("raw-secret-android-id", "cert", "com.xgy.lansms");
        byte[] salt = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        assertFalse(identity.backupId.contains("raw-secret"));
        assertFalse(identity.machineCode.contains("raw-secret"));
        assertFalse(Arrays.equals(identity.backupKey, identity.deriveBackupKey(salt)));
    }

    @Test public void emptyIdentityMaterialIsRejected() throws Exception {
        try {
            DeviceIdentity.derive("", "cert", "com.xgy.lansms");
            fail("empty Android ID must be rejected");
        } catch (IllegalArgumentException expected) { }
    }
}
