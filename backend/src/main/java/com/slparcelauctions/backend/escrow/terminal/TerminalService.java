package com.slparcelauctions.backend.escrow.terminal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.escrow.exception.TerminalAuthException;
import com.slparcelauctions.backend.escrow.terminal.dto.TerminalRegisterRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Registers and heartbeats in-world escrow terminals. Upserts by
 * {@code terminalId}: re-registration from the same object UUID updates
 * {@code httpInUrl} / {@code regionName} / {@code lastSeenAt} and
 * re-activates the row.
 *
 * <p>Every SL callback that runs through this service goes through
 * {@link #assertSharedSecret(String)} before any side effect; terminals
 * sending the wrong secret get 403 {@code SECRET_MISMATCH} via
 * {@link TerminalAuthException}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TerminalService {

    private final TerminalRepository terminalRepo;
    private final EscrowConfigProperties props;
    private final Clock clock;

    @Transactional
    public Terminal register(TerminalRegisterRequest req) {
        assertSharedSecret(req.sharedSecret());
        OffsetDateTime now = OffsetDateTime.now(clock);
        Terminal t = terminalRepo.findById(req.terminalId())
                .orElseGet(() -> Terminal.builder()
                        .terminalId(req.terminalId())
                        .active(true)
                        .build());
        t.setHttpInUrl(req.httpInUrl());
        t.setRegionName(req.regionName());
        t.setLastSeenAt(now);
        t.setActive(true);
        Terminal saved = terminalRepo.save(t);
        log.info("Terminal {} registered (url={}, region={})", saved.getTerminalId(),
                saved.getHttpInUrl(), saved.getRegionName());
        return saved;
    }

    /**
     * Validates the caller-supplied shared secret against the configured
     * {@code slpa.escrow.terminal-shared-secret}. The byte comparison uses
     * {@link MessageDigest#isEqual(byte[], byte[])} so the check runs in
     * constant time regardless of how many leading bytes match — this
     * mitigates timing attacks where an attacker measures response latency
     * to brute-force the static server-side secret one byte at a time.
     * Null / blank guards are kept as a fast fail path since they short-circuit
     * on configuration errors, not on attacker-controlled input.
     */
    /**
     * Refreshes {@code lastSeenAt} on the given terminal to {@code now}, so
     * the dispatcher's {@code findAnyLive} window (active=true AND
     * lastSeenAt within the live window) keeps the terminal in rotation.
     *
     * <p>Call this from any successful authenticated SL terminal callback —
     * deposit, withdraw-request, payout-result, heartbeat — so the terminal
     * stays live in the dispatcher's view as long as it is actively serving
     * traffic, even if it hasn't re-registered recently.
     *
     * <p>No-op if the terminal id doesn't resolve (defensive — the caller
     * has already validated existence by this point in production paths).
     */
    @Transactional
    public void markSeen(String terminalId) {
        terminalRepo.findById(terminalId).ifPresent(t -> {
            t.setLastSeenAt(OffsetDateTime.now(clock));
            terminalRepo.save(t);
        });
    }

    public void assertSharedSecret(String provided) {
        String expected = props.terminalSharedSecret();
        if (expected == null || expected.isBlank() || provided == null) {
            log.warn("Shared secret mismatch on terminal call (null/blank)");
            throw new TerminalAuthException();
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedBytes, providedBytes)) {
            log.warn("Shared secret mismatch on terminal call");
            throw new TerminalAuthException();
        }
    }
}
