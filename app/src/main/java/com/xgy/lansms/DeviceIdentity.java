package com.xgy.lansms;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateEncodingException;
import java.util.Arrays;
import java.util.Locale;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Stable, non-secret identity for the encrypted device backup.
 *
 * The raw ANDROID_ID is deliberately never returned or sent over the network.
 * It is combined with the signing certificate and fed through a versioned
 * PBKDF2 derivation. Reinstalling the same signed application on the same
 * Android user therefore keeps the identity, while a factory reset or a
 * different signing certificate produces a different identity.
 */
public final class DeviceIdentity {
    public static final int DERIVATION_VERSION = 1;
    public static final int PBKDF2_ITERATIONS = 200_000;
    private static final byte[] SALT = "xgy-device-backup-v1".getBytes(StandardCharsets.UTF_8);

    public final String backupId;
    public final String machineCode;
    public final byte[] backupKey;
    public final String backupToken;

    private DeviceIdentity(String backupId, String machineCode, byte[] backupKey, String backupToken) {
        this.backupId = backupId;
        this.machineCode = machineCode;
        this.backupKey = backupKey;
        this.backupToken = backupToken;
    }

    /** Derives the per-backup AES key from the stable root and random backup salt. */
    public byte[] deriveBackupKey(byte[] salt) throws Exception {
        if (salt == null || salt.length != 16) throw new IllegalArgumentException("backup salt must be exactly 16 bytes");
        return CloudCrypto.hkdfSha256(backupKey, salt,
                ("xgy-device-backup-aes-v" + DERIVATION_VERSION + "\n" + backupId)
                        .getBytes(StandardCharsets.UTF_8), 32);
    }

    /** Expensive: call from a worker thread, never from an Activity callback. */
    public static DeviceIdentity from(Context context) throws Exception {
        Context app = context.getApplicationContext();
        String androidId = Settings.Secure.getString(app.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.trim().isEmpty()) throw new IllegalStateException("Android 设备编号不可用，无法安全恢复云链路");
        String certificate = signingCertificateSha256(app);
        return derive(androidId, certificate, app.getPackageName());
    }

    /** Pure derivation function used by unit tests and by the runtime identity. */
    public static DeviceIdentity derive(String androidId, String signingCertificateSha256, String packageName)
            throws Exception {
        if (safe(androidId).isEmpty() || safe(signingCertificateSha256).isEmpty() || safe(packageName).isEmpty()) {
            throw new IllegalArgumentException("device identity inputs must not be empty");
        }
        String material = "xgy-device-identity-v" + DERIVATION_VERSION + "\n"
                + safe(androidId) + "\n" + safe(signingCertificateSha256) + "\n" + safe(packageName);
        PBEKeySpec spec = new PBEKeySpec(material.toCharArray(), SALT, PBKDF2_ITERATIONS, 512);
        byte[] derived;
        try {
            derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
        byte[] key = Arrays.copyOfRange(derived, 0, 32);
        byte[] tokenBytes = Arrays.copyOfRange(derived, 32, 64);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("xgy-device-backup-id-v1\n".getBytes(StandardCharsets.UTF_8));
        digest.update(tokenBytes);
        String backupId = "backup_" + hex(digest.digest());
        String machineCode = formatMachineCode(backupId);
        return new DeviceIdentity(backupId, machineCode, key, CloudCrypto.b64(tokenBytes));
    }

    /** Returns the SHA-256 fingerprint of the currently installed signing cert. */
    public static String signingCertificateSha256(Context context) throws Exception {
        PackageManager pm = context.getPackageManager();
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            signatures = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo.getApkContentsSigners();
        } else {
            signatures = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
        }
        if (signatures == null || signatures.length == 0) throw new CertificateEncodingException("missing signing certificate");
        byte[] encoded = signatures[0].toByteArray();
        return hex(MessageDigest.getInstance("SHA-256").digest(encoded));
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }

    private static String formatMachineCode(String backupId) {
        String compact = backupId == null ? "" : backupId.replace("backup_", "").replace("-", "").toUpperCase(Locale.ROOT);
        if (compact.length() > 16) compact = compact.substring(0, 16);
        StringBuilder out = new StringBuilder("XGY-");
        for (int i = 0; i < compact.length(); i++) {
            if (i > 0 && i % 4 == 0) out.append('-');
            out.append(compact.charAt(i));
        }
        return out.toString();
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }
}
