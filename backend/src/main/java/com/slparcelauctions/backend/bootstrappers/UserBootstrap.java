package com.slparcelauctions.backend.bootstrappers;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds the primary admin account on a fresh database. Runs on every
 * {@link ApplicationReadyEvent} but is idempotent: it only inserts when the
 * users table is empty or the seed email is absent.
 *
 * <p>Order {@code 1} so future bootstrappers can layer behind this one with
 * higher order values.
 *
 * <p>The bcrypt hash and SL avatar identifiers below are a Day-1 snapshot of
 * the real account; the seed user changes their password through the UI as
 * normal and the row diverges from this constant — that is expected.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserBootstrap {

    private static final String SEED_EMAIL = "heath@slparcels.com";
    private static final String SEED_USERNAME = "Heath Onyx";
    private static final String SEED_PASSWORD_HASH =
            "$2a$10$SQ3zOTDlerPGlxGainZ1P.hz/kpjVbaqekPi2FK2nl5BRMzKMG80q";
    private static final UUID SEED_SL_AVATAR_UUID =
            UUID.fromString("aa87bc38-c175-427d-b665-02e6838963cc");
    private static final String SEED_SL_AVATAR_NAME = "Heath Onyx";
    private static final String SEED_SL_USERNAME = "heath.onyx";
    private static final String SEED_SL_DISPLAY_NAME = "Heath Onyx";
    private static final LocalDate SEED_SL_BORN_DATE = LocalDate.of(2007, 12, 24);
    private static final int SEED_SL_PAYINFO = 3;
    private static final OffsetDateTime SEED_VERIFIED_AT =
            OffsetDateTime.parse("2026-05-04T17:00:37.917517Z");

    private final UserRepository userRepository;
    private final BootstrapUserFactory bootstrapUserFactory;

    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void bootstrap() {
        boolean dbEmpty = userRepository.count() == 0;
        boolean seedMissing = userRepository.findByEmail(SEED_EMAIL).isEmpty();
        if (!dbEmpty && !seedMissing) {
            log.info("UserBootstrap: seed user {} already present, skipping.", SEED_EMAIL);
            return;
        }
        BootstrapUserSpec spec = new BootstrapUserSpec(
                SEED_USERNAME,
                SEED_EMAIL,
                SEED_PASSWORD_HASH,
                SEED_SL_AVATAR_UUID,
                SEED_SL_AVATAR_NAME,
                SEED_SL_USERNAME,
                SEED_SL_DISPLAY_NAME,
                SEED_SL_BORN_DATE,
                SEED_SL_PAYINFO,
                null,
                null,
                null,
                Role.ADMIN,
                true,
                SEED_VERIFIED_AT,
                false);
        bootstrapUserFactory.createUser(spec);
        log.info("UserBootstrap: seeded admin user {} (dbEmpty={}, seedMissing={}).",
                SEED_EMAIL, dbEmpty, seedMissing);
    }
}
