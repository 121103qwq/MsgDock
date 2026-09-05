package com.xgy.lansms;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EncodedKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Protocol v2 cryptography. Binary values are unpadded base64url. */
public final class CloudCrypto {
    private static final String CURVE = "secp256r1";
    private static final String HKDF_INFO = "xgy-sms-room-key";
    private static final SecureRandom RANDOM = new SecureRandom();

    private CloudCrypto() {}

    public static KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(CURVE), RANDOM);
        return generator.generateKeyPair();
    }

    public static String encodePublicKey(PublicKey key) {
        if (!(key instanceof ECPublicKey)) throw new IllegalArgumentException("not an EC public key");
        ECPublicKey ec = (ECPublicKey) key;
        byte[] out = new byte[65];
        out[0] = 0x04;
        put32(ec.getW().getAffineX(), out, 1);
        put32(ec.getW().getAffineY(), out, 33);
        return b64(out);
    }

    /** Stores only the raw 32-byte private scalar, never a device public key blob. */
    public static String encodePrivateKey(PrivateKey key) {
        if (!(key instanceof ECPrivateKey)) throw new IllegalArgumentException("not an EC private key");
        byte[] out = new byte[32];
        put32(((ECPrivateKey) key).getS(), out, 0);
        return b64(out);
    }

    public static PublicKey decodePublicKey(String encoded) throws GeneralSecurityException {
        byte[] raw = unb64(encoded);
        if (raw.length != 65 || raw[0] != 0x04) throw new GeneralSecurityException("invalid SEC1 P-256 public key");
        ECParameterSpec params = parameters();
        ECPublicKeySpec spec = new ECPublicKeySpec(
                new java.security.spec.ECPoint(new BigInteger(1, Arrays.copyOfRange(raw, 1, 33)),
                        new BigInteger(1, Arrays.copyOfRange(raw, 33, 65))), params);
        return KeyFactory.getInstance("EC").generatePublic(spec);
    }

    public static PrivateKey decodePrivateKey(String encoded) throws GeneralSecurityException {
        byte[] raw = unb64(encoded);
        KeyFactory factory = KeyFactory.getInstance("EC");
        if (raw.length <= 32) {
            if (raw.length == 0) throw new GeneralSecurityException("empty private key");
            return factory.generatePrivate(new ECPrivateKeySpec(new BigInteger(1, raw), parameters()));
        }
        EncodedKeySpec spec = new PKCS8EncodedKeySpec(raw);
        return factory.generatePrivate(spec);
    }

    public static byte[] sharedSecret(PrivateKey privateKey, PublicKey peerPublicKey) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(privateKey);
        agreement.doPhase(peerPublicKey, true);
        return agreement.generateSecret();
    }

    public static byte[] deriveRoomKey(PrivateKey privateKey, PublicKey peerPublicKey, String roomId)
            throws GeneralSecurityException {
        byte[] salt = MessageDigest.getInstance("SHA-256")
                .digest(("xgy-sms-v2:" + roomId).getBytes(StandardCharsets.UTF_8));
        return hkdfSha256(sharedSecret(privateKey, peerPublicKey), salt,
                HKDF_INFO.getBytes(StandardCharsets.UTF_8), 32);
    }

    public static Encrypted encrypt(byte[] key, byte[] plaintext, byte[] aad) throws GeneralSecurityException {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return new Encrypted(nonce, cipher.doFinal(plaintext));
    }

    public static byte[] decrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] aad)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(ciphertext);
    }

    public static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        byte[] result = new byte[length];
        byte[] previous = new byte[0];
        int offset = 0;
        int counter = 1;
        while (offset < length) {
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(previous);
            mac.update(info);
            mac.update((byte) counter++);
            previous = mac.doFinal();
            int copy = Math.min(previous.length, length - offset);
            System.arraycopy(previous, 0, result, offset, copy);
            offset += copy;
        }
        return result;
    }

    public static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static byte[] unb64(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    public static String aad(String id, String roomId, String senderDeviceId, String targetDeviceId) {
        return "xgy-sms-v2\n" + id + "\n" + roomId + "\n" + senderDeviceId + "\n" + targetDeviceId;
    }

    private static ECParameterSpec parameters() throws GeneralSecurityException {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec(CURVE));
        return parameters.getParameterSpec(ECParameterSpec.class);
    }

    private static void put32(BigInteger value, byte[] target, int offset) {
        byte[] source = value.toByteArray();
        int sourceOffset = source.length > 32 ? source.length - 32 : 0;
        int length = source.length - sourceOffset;
        Arrays.fill(target, offset, offset + 32, (byte) 0);
        System.arraycopy(source, sourceOffset, target, offset + 32 - length, length);
    }

    public static final class Encrypted {
        public final byte[] nonce;
        public final byte[] ciphertext;

        Encrypted(byte[] nonce, byte[] ciphertext) {
            this.nonce = nonce;
            this.ciphertext = ciphertext;
        }
    }
}
