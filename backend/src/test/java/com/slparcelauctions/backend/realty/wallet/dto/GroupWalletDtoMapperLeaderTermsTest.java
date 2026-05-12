package com.slparcelauctions.backend.realty.wallet.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.user.User;

/**
 * Sub-project G §7.5 — confirms {@link GroupWalletDtoMapper#toWalletDto} copies
 * the leader's wallet-terms acceptance timestamp into {@link GroupWalletDto}
 * so the frontend leader-terms-block banner has a signal to render against.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false"
})
@Transactional
class GroupWalletDtoMapperLeaderTermsTest {

    @Autowired GroupWalletDtoMapper mapper;

    @Test
    void buildWalletDto_carries_leaderWalletTermsAcceptedAt_when_set() {
        Instant ts = Instant.parse("2026-04-01T10:00:00Z");
        User leader = User.builder().username("leader-a")
                .walletTermsAcceptedAt(OffsetDateTime.ofInstant(ts, java.time.ZoneOffset.UTC))
                .build();

        GroupWalletDto dto = mapper.toWalletDto(1000L, 100L, 900L, leader, List.of());

        assertThat(dto.leaderTermsAcceptedAt()).isEqualTo(ts);
    }

    @Test
    void buildWalletDto_carries_null_when_leaderHasNotAcceptedTerms() {
        User leader = User.builder().username("leader-b").build();

        GroupWalletDto dto = mapper.toWalletDto(0L, 0L, 0L, leader, List.of());

        assertThat(dto.leaderTermsAcceptedAt()).isNull();
    }
}
