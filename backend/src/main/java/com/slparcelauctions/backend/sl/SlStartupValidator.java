package com.slparcelauctions.backend.sl;

import java.util.Arrays;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Fails fast on startup in the {@code prod} profile if no trusted SL owner keys
 * are configured. Non-prod profiles log a warning but continue so dev / test
 * profiles can boot without secret material.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlStartupValidator {

    private final SlConfigProperties props;
    private final Environment environment;

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (isProd && props.trustedOwnerKeys().isEmpty()) {
            throw new IllegalStateException(
                    "slpa.sl.trusted-owner-keys is empty in prod profile - "
                            + "refusing to start. Configure at least one UUID.");
        }
        if (props.trustedOwnerKeys().isEmpty()) {
            log.warn("slpa.sl.trusted-owner-keys is empty - all /api/v1/sl/verify "
                    + "calls will be rejected. (non-prod profile, not fatal.)");
        } else {
            log.info("SL integration configured with {} trusted owner key(s)",
                    props.trustedOwnerKeys().size());
        }
    }
}
