package com.slparcelauctions.backend.auth;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TokenHasherTest {

    @Test
    void sha256Hex_producesStable64CharHex() {
        String hash = TokenHasher.sha256Hex("test-token-value");

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
        // Same input → same output (deterministic)
        assertThat(hash).isEqualTo(TokenHasher.sha256Hex("test-token-value"));
    }

    @Test
    void sha256Hex_differentInputsProduceDifferentHashes() {
        assertThat(TokenHasher.sha256Hex("a")).isNotEqualTo(TokenHasher.sha256Hex("b"));
    }

    @Test
    void secureRandomBase64Url_produces43CharTokenFor32Bytes() {
        String token = TokenHasher.secureRandomBase64Url(32);

        // base64url of 32 bytes with no padding = 43 chars
        assertThat(token).hasSize(43);
        // URL-safe base64 alphabet: A-Z a-z 0-9 - _
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void secureRandomBase64Url_producesUniqueValues() {
        String a = TokenHasher.secureRandomBase64Url(32);
        String b = TokenHasher.secureRandomBase64Url(32);

        assertThat(a).isNotEqualTo(b);
    }
}
