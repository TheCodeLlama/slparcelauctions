package com.slparcelauctions.backend.escrow.config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.escrow.terminal.EscrowConfigProperties;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Fails fast on non-dev profiles when {@code slpa.escrow.terminal-shared-secret}
 * is unset or too short. The dev profile skips this validator entirely so
 * local development can boot on the hard-coded dev secret without ceremony.
 *
 * <p>Validation runs at {@code @PostConstruct} so Spring aborts the context
 * startup before any listener threads can accept traffic — matches
 * {@link com.slparcelauctions.backend.sl.SlStartupValidator} in intent
 * (fail-fast) while using the PostConstruct hook instead of
 * {@code ApplicationReadyEvent}: escrow secret material must be present
 * before any bean that depends on it is wired, not just by the time the
 * HTTP listener opens.
 */
@Component
@Profile("!dev")
@RequiredArgsConstructor
@Slf4j
public class EscrowStartupValidator {

    private final EscrowConfigProperties props;

    @PostConstruct
    void validate() {
        if (props.terminalSharedSecret() == null || props.terminalSharedSecret().isBlank()) {
            throw new IllegalStateException(
                    "slpa.escrow.terminal-shared-secret must be set in non-dev profiles. "
                            + "Configure via environment variable SLPA_ESCROW_TERMINAL_SHARED_SECRET "
                            + "or a secrets-manager override.");
        }
        if (props.terminalSharedSecret().length() < 16) {
            throw new IllegalStateException(
                    "slpa.escrow.terminal-shared-secret must be at least 16 characters; got "
                            + props.terminalSharedSecret().length());
        }
        log.info("Escrow startup validation OK: liveWindow={}, inFlightTimeout={}",
                props.terminalLiveWindow(), props.commandInFlightTimeout());
    }
}
