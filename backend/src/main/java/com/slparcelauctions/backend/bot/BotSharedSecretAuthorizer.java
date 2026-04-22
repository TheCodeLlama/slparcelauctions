package com.slparcelauctions.backend.bot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * Validates the {@code Authorization: Bearer <secret>} header on
 * {@code /api/v1/bot/**} requests. Mirrors the escrow terminal secret
 * pattern in
 * {@link com.slparcelauctions.backend.escrow.terminal.TerminalService#assertSharedSecret(String)}.
 * Uses {@link MessageDigest#isEqual(byte[], byte[])} for a constant-time
 * compare that does not leak secret length via timing.
 *
 * <p>A {@code null} or blank expected secret (no config set) fails closed —
 * authorizer returns {@code false}. Non-dev profiles also have
 * {@link BotStartupValidator} to fail fast at boot in that scenario; this
 * extra guard keeps misconfigured dev profiles from silently authorizing
 * every request too.
 */
@Component
@RequiredArgsConstructor
public class BotSharedSecretAuthorizer {

    private static final String BEARER_PREFIX = "Bearer ";

    private final BotTaskConfigProperties props;

    public boolean isAuthorized(HttpServletRequest request) {
        String expected = props.bot() == null ? null : props.bot().sharedSecret();
        if (expected == null || expected.isBlank()) {
            return false;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return false;
        }
        String presented = header.substring(BEARER_PREFIX.length());

        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] presentedBytes = presented.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, presentedBytes);
    }
}
