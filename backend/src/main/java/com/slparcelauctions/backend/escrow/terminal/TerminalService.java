package com.slparcelauctions.backend.escrow.terminal;

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

    public void assertSharedSecret(String provided) {
        String expected = props.terminalSharedSecret();
        if (expected == null || expected.isBlank() || provided == null
                || !expected.equals(provided)) {
            log.warn("Shared secret mismatch on terminal call");
            throw new TerminalAuthException();
        }
    }
}
