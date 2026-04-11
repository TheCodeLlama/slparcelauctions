package com.slparcelauctions.backend.auth;

import org.junit.jupiter.api.Test;
import javax.crypto.SecretKey;
import java.util.Base64;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtKeyFactoryTest {

    @Test
    void buildKey_succeedsWith32ByteSecret() {
        byte[] bytes = new byte[32];
        for (int i = 0; i < 32; i++) bytes[i] = (byte) i;
        String base64 = Base64.getEncoder().encodeToString(bytes);

        SecretKey key = JwtKeyFactory.buildKey(base64);

        assertThat(key).isNotNull();
        assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
    }

    @Test
    void buildKey_throwsOnShortSecret() {
        byte[] bytes = new byte[16]; // too short
        String base64 = Base64.getEncoder().encodeToString(bytes);

        assertThatThrownBy(() -> JwtKeyFactory.buildKey(base64))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void buildKey_throwsOnMalformedBase64() {
        // Non-base64 input — decoder should reject this, which becomes an exception caller
        // propagation path. The exact exception type comes from JJWT's Decoders.BASE64.decode
        // (DecodingException, which wraps IllegalArgumentException). We assert on a broad
        // supertype to stay resilient across minor JJWT versions.
        assertThatThrownBy(() -> JwtKeyFactory.buildKey("!!! not valid base64 !!!"))
            .isInstanceOf(RuntimeException.class);
    }
}
