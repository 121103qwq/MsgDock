package com.xgy.lansms;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;

/** Cross-language checks for PROTOCOL_V2_TEST_VECTOR.json. */
public class CloudCryptoTest {
    @Test public void protocolVectorUsesP256HkdfAndAesGcm() throws Exception {
        PrivateKey privateA = CloudCrypto.decodePrivateKey("AQ");
        PublicKey publicB = CloudCrypto.decodePublicKey("BHzyexiNA09-ilI4AwS1GsPAiWnid_IbNaYLSPxHZpl4B3dVENuO0EApPZrGn3Qw27p9reY86YIpngS3nSJ4c9E");
        byte[] shared = CloudCrypto.sharedSecret(privateA, publicB);
        assertEquals("fPJ7GI0DT36KUjgDBLUaw8CJaeJ38hs1pgtI_EdmmXg", CloudCrypto.b64(shared));
        byte[] key = CloudCrypto.deriveRoomKey(privateA, publicB, "room_test");
        assertEquals("uyxWz213tIPGxF4JXseW9uQb4tsLvfssqpnj5LuBbSk", CloudCrypto.b64(key));

        String aad = "xgy-sms-v2\n00000000-0000-4000-8000-000000000001\nroom_test\nphone_test\nwin_test";
        byte[] plaintext = CloudCrypto.decrypt(key, CloudCrypto.unb64("AAECAwQFBgcICQoL"),
                CloudCrypto.unb64("mIvB_-dGCueU53_hyEYuBaxg4nfMGqN-IcgzkX-Xe3DsVKQ_J3QWJEDyjH9ah_k9z9NBgGKB5LkE3nxOnM7C2BFMQ6Jcop3-kXO-XQ5mTq3WTO-b_qRFDUxXDey8IPk3fprGDmIjzBtyY0ji8I9f83TN3ZDGL6mgRYxw"),
                aad.getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("{\"v\":2,\"from\":\"10086\",\"text\":\"验证码 123456\",\"receivedAt\":1787400001000,\"sim\":1,\"device\":\"Android Test\"}".getBytes(StandardCharsets.UTF_8), plaintext);
    }
}
