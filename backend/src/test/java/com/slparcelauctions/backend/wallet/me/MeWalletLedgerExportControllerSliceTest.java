package com.slparcelauctions.backend.wallet.me;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.UserLedgerEntry;
import com.slparcelauctions.backend.wallet.UserLedgerEntryType;
import com.slparcelauctions.backend.wallet.UserLedgerRepository;

/**
 * Slice-style coverage for {@code GET /api/v1/me/wallet/ledger/export.csv}.
 *
 * <p>Asserts the non-streaming paths (auth gate + rate-limit). The streaming
 * body path (CSV header + content escaping) is covered by
 * {@link LedgerCsvWriterTest} for writer correctness; testing the full async
 * + StreamingResponseBody + Spring Security wiring through MockMvc is fragile
 * (the security entry point and the streaming output-stream contend on the
 * MockHttpServletResponse), so the body assertions live at the unit level.
 */
@SpringBootTest
@AutoConfigureMockMvc
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
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
class MeWalletLedgerExportControllerSliceTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;
    @Autowired UserLedgerRepository ledgerRepository;

    private Long userId;
    private String accessToken;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        User user = userRepository.save(User.builder()
                .email("ledger-export-" + suffix + "@example.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .balanceLindens(0L)
                .reservedLindens(0L)
                .build());
        userId = user.getId();
        accessToken = jwtService.issueAccessToken(
                new AuthPrincipal(userId, user.getPublicId(), user.getEmail(), 0L, Role.USER));

        // Seed one ledger row so happy-path test has something to stream.
        ledgerRepository.save(UserLedgerEntry.builder()
                .userId(userId)
                .entryType(UserLedgerEntryType.DEPOSIT)
                .amount(100L)
                .balanceAfter(100L)
                .reservedAfter(0L)
                .build());
    }

    private String authHeader() {
        return "Bearer " + accessToken;
    }

    @Test
    void exportLedger_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/me/wallet/ledger/export.csv"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exportLedger_secondCallWithin60s_returns429() throws Exception {
        // First call passes through to the streaming body — for this test we
        // don't need to consume the body, only confirm the rate limiter
        // recorded the user. Spring will start the async dispatch but the
        // controller's tryAcquire side effect already happened synchronously
        // before returning the StreamingResponseBody.
        mockMvc.perform(get("/api/v1/me/wallet/ledger/export.csv")
                        .header("Authorization", authHeader()));

        // Second call from the same user inside the 60s window returns 429
        // synchronously (no streaming body, no async).
        mockMvc.perform(get("/api/v1/me/wallet/ledger/export.csv")
                        .header("Authorization", authHeader()))
                .andExpect(status().is(429));
    }
}
