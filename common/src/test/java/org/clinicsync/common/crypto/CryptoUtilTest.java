package org.clinicsync.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;

class CryptoUtilTest {

    @Test
    @DisplayName("Canonical JSON should sort keys and produce identical bytes for unordered inputs")
    void testCanonicalJson() {
        String jsonA = "{\"b\": 2, \"a\": 1, \"nested\": {\"z\": 10, \"m\": 5}}";
        String jsonB = "{\"nested\": {\"m\": 5, \"z\": 10}, \"a\": 1, \"b\": 2}";

        byte[] canonicalA = CanonicalJsonUtil.canonicalize(jsonA);
        byte[] canonicalB = CanonicalJsonUtil.canonicalize(jsonB);

        assertThat(canonicalA).isEqualTo(canonicalB);
        assertThat(new String(canonicalA)).isEqualTo("{\"a\":1,\"b\":2,\"nested\":{\"m\":5,\"z\":10}}");
    }

    @Test
    @DisplayName("Ed25519 should generate keys, sign canonical data, and verify correctly")
    void testEd25519SigningAndVerification() {
        KeyPair keyPair = Ed25519Util.generateKeyPair();
        String publicKeyBase64 = Ed25519Util.encodePublicKey(keyPair.getPublic());
        String privateKeyBase64 = Ed25519Util.encodePrivateKey(keyPair.getPrivate());

        assertThat(Ed25519Util.decodePublicKey(publicKeyBase64)).isEqualTo(keyPair.getPublic());
        assertThat(Ed25519Util.decodePrivateKey(privateKeyBase64)).isEqualTo(keyPair.getPrivate());

        byte[] payload = CanonicalJsonUtil.canonicalize("{\"batchId\":\"batch-1\",\"changes\":[]}");
        String signature = Ed25519Util.sign(keyPair.getPrivate(), payload);

        assertThat(Ed25519Util.verify(keyPair.getPublic(), payload, signature)).isTrue();

        // Tampered payload fails verification (Invariant 7 - Integrity)
        byte[] tamperedPayload = CanonicalJsonUtil.canonicalize("{\"batchId\":\"batch-1\",\"changes\":[{\"tampered\":true}]}");
        assertThat(Ed25519Util.verify(keyPair.getPublic(), tamperedPayload, signature)).isFalse();

        // Wrong key fails verification
        KeyPair otherKeyPair = Ed25519Util.generateKeyPair();
        assertThat(Ed25519Util.verify(otherKeyPair.getPublic(), payload, signature)).isFalse();
    }
}
