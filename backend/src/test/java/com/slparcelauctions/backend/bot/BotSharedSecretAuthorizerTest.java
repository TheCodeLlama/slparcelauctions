package com.slparcelauctions.backend.bot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.slparcelauctions.backend.bot.BotTaskConfigProperties.Bot;
import com.slparcelauctions.backend.bot.BotTaskConfigProperties.BotTask;

class BotSharedSecretAuthorizerTest {

    private final BotTaskConfigProperties props = new BotTaskConfigProperties(
            new Bot("secret-value-12345678", null, null, 3, 6),
            new BotTask(0L, null, null, null, null));

    private final BotSharedSecretAuthorizer authorizer = new BotSharedSecretAuthorizer(props);

    @Test
    void matchingBearerToken_isAuthorized() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer secret-value-12345678");
        assertThat(authorizer.isAuthorized(req)).isTrue();
    }

    @Test
    void missingHeader_isDenied() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        assertThat(authorizer.isAuthorized(req)).isFalse();
    }

    @Test
    void wrongScheme_isDenied() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Basic secret-value-12345678");
        assertThat(authorizer.isAuthorized(req)).isFalse();
    }

    @Test
    void mismatchedToken_isDenied() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer wrong-value-12345678");
        assertThat(authorizer.isAuthorized(req)).isFalse();
    }

    @Test
    void differentLengthToken_isDenied_constantTime() {
        // Regression: a naive String.equals short-circuits on length mismatch,
        // leaking length via timing. MessageDigest.isEqual does not.
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer short");
        assertThat(authorizer.isAuthorized(req)).isFalse();
    }
}
