package com.slparcelauctions.backend.wallet.me;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.UserLedgerEntry;
import com.slparcelauctions.backend.wallet.UserLedgerEntryType;
import com.slparcelauctions.backend.wallet.UserLedgerRepository;

/**
 * Slice-style coverage for {@code GET /api/v1/me/wallet/ledger}. Seeds a user
 * + a small ledger fixture directly through the repositories, mints a real
 * JWT for that user via {@link JwtService}, and asserts the
 * pagination + filter wiring on the endpoint.
 *
 * <p>This is a {@code @SpringBootTest} integration-style slice rather than a
 * pure {@code @WebMvcTest} because the endpoint reads through Hibernate via
 * {@link UserLedgerRepository}'s {@code JpaSpecificationExecutor} —
 * exercising the spec is the entire point of the test, so a real DB is
 * required. Heavy schedulers + cleanups are disabled via
 * {@link TestPropertySource}.
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
class MeWalletLedgerControllerSliceTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;
    @Autowired UserLedgerRepository ledgerRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Long userId;
    private String accessToken;

    @BeforeEach
    void setUp() {
        // Each test gets its own user so seeded ledger rows are scoped and
        // don't leak across tests when the suite shares a DB.
        String suffix = UUID.randomUUID().toString();
        User user = userRepository.save(User.builder()
                .email("ledger-slice-" + suffix + "@example.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .balanceLindens(0L)
                .reservedLindens(0L)
                .build());
        userId = user.getId();
        accessToken = jwtService.issueAccessToken(
                new AuthPrincipal(userId, user.getEmail(), 0L, Role.USER));
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                            */
    /* ------------------------------------------------------------------ */

    private UserLedgerEntry seedEntry(
            UserLedgerEntryType type, long amount, long balanceAfter, OffsetDateTime createdAt) {
        return ledgerRepository.save(UserLedgerEntry.builder()
                .userId(userId)
                .entryType(type)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .reservedAfter(0L)
                .createdAt(createdAt)
                .build());
    }

    private String authHeader() {
        return "Bearer " + accessToken;
    }

    /* ------------------------------------------------------------------ */
    /* Tests                                                              */
    /* ------------------------------------------------------------------ */

    @Test
    void noFilters_returnsFirstPage_sortedDescByCreatedAt() throws Exception {
        OffsetDateTime base = OffsetDateTime.parse("2026-04-15T10:00:00Z");
        seedEntry(UserLedgerEntryType.DEPOSIT, 100L, 100L, base);
        seedEntry(UserLedgerEntryType.DEPOSIT, 200L, 300L, base.plusMinutes(5));
        seedEntry(UserLedgerEntryType.DEPOSIT, 300L, 600L, base.plusMinutes(10));

        MvcResult result = mockMvc.perform(get("/api/v1/me/wallet/ledger")
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andReturn();

        JsonNode entries = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("entries");
        assertThat(entries.size()).isEqualTo(3);
        // DESC by createdAt: 300 (newest) -> 200 -> 100 (oldest).
        assertThat(entries.get(0).get("amount").asLong()).isEqualTo(300L);
        assertThat(entries.get(1).get("amount").asLong()).isEqualTo(200L);
        assertThat(entries.get(2).get("amount").asLong()).isEqualTo(100L);
    }

    @Test
    void entryTypeFilter_returnsOnlyMatchingTypes() throws Exception {
        OffsetDateTime base = OffsetDateTime.parse("2026-04-15T10:00:00Z");
        seedEntry(UserLedgerEntryType.DEPOSIT, 100L, 100L, base);
        seedEntry(UserLedgerEntryType.WITHDRAW_QUEUED, 50L, 50L, base.plusMinutes(1));
        seedEntry(UserLedgerEntryType.BID_RESERVED, 20L, 50L, base.plusMinutes(2));
        seedEntry(UserLedgerEntryType.LISTING_FEE_DEBIT, 10L, 40L, base.plusMinutes(3));

        MvcResult result = mockMvc.perform(get("/api/v1/me/wallet/ledger")
                        .param("entryType", "DEPOSIT", "WITHDRAW_QUEUED")
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn();

        JsonNode entries = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("entries");
        for (JsonNode e : entries) {
            assertThat(e.get("entryType").asText())
                    .isIn("DEPOSIT", "WITHDRAW_QUEUED");
        }
    }

    @Test
    void dateRangeFilter_excludesOutsideRange() throws Exception {
        // before range, in range, after range.
        seedEntry(UserLedgerEntryType.DEPOSIT, 100L, 100L,
                OffsetDateTime.parse("2026-03-15T10:00:00Z"));
        seedEntry(UserLedgerEntryType.DEPOSIT, 200L, 300L,
                OffsetDateTime.parse("2026-04-15T10:00:00Z"));
        // exclusive upper bound — boundary equal to "to" must be excluded.
        seedEntry(UserLedgerEntryType.DEPOSIT, 300L, 600L,
                OffsetDateTime.parse("2026-05-01T00:00:00Z"));
        seedEntry(UserLedgerEntryType.DEPOSIT, 400L, 1000L,
                OffsetDateTime.parse("2026-05-15T10:00:00Z"));

        MvcResult result = mockMvc.perform(get("/api/v1/me/wallet/ledger")
                        .param("from", "2026-04-01T00:00:00Z")
                        .param("to", "2026-05-01T00:00:00Z")
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn();

        JsonNode entries = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("entries");
        assertThat(entries.size()).isEqualTo(1);
        assertThat(entries.get(0).get("amount").asLong()).isEqualTo(200L);
    }

    @Test
    void amountRangeFilter_returnsOnlyInRange() throws Exception {
        OffsetDateTime base = OffsetDateTime.parse("2026-04-15T10:00:00Z");
        seedEntry(UserLedgerEntryType.DEPOSIT, 50L, 50L, base);
        seedEntry(UserLedgerEntryType.DEPOSIT, 100L, 150L, base.plusMinutes(1));
        seedEntry(UserLedgerEntryType.DEPOSIT, 250L, 400L, base.plusMinutes(2));
        seedEntry(UserLedgerEntryType.DEPOSIT, 500L, 900L, base.plusMinutes(3));
        seedEntry(UserLedgerEntryType.DEPOSIT, 1000L, 1900L, base.plusMinutes(4));

        MvcResult result = mockMvc.perform(get("/api/v1/me/wallet/ledger")
                        .param("amountMin", "100")
                        .param("amountMax", "500")
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andReturn();

        JsonNode entries = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("entries");
        for (JsonNode e : entries) {
            long a = e.get("amount").asLong();
            assertThat(a).isBetween(100L, 500L);
        }
    }

    @Test
    void pageAndSize_returnsCorrectSlice() throws Exception {
        OffsetDateTime base = OffsetDateTime.parse("2026-04-15T10:00:00Z");
        // Seed 25 entries, each one minute apart, increasing amount so we
        // can identify which slice came back.
        for (int i = 0; i < 25; i++) {
            seedEntry(UserLedgerEntryType.DEPOSIT, (long) (i + 1), (long) (i + 1),
                    base.plusMinutes(i));
        }

        MvcResult result = mockMvc.perform(get("/api/v1/me/wallet/ledger")
                        .param("page", "1")
                        .param("size", "10")
                        .header("Authorization", authHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andReturn();

        JsonNode entries = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("entries");
        assertThat(entries.size()).isEqualTo(10);
        // DESC by createdAt: page 0 = entries 25..16 (amounts 25..16),
        // page 1 = entries 15..6 (amounts 15..6), page 2 = entries 5..1.
        assertThat(entries.get(0).get("amount").asLong()).isEqualTo(15L);
        assertThat(entries.get(9).get("amount").asLong()).isEqualTo(6L);
    }

    @Test
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/me/wallet/ledger"))
                .andExpect(status().isUnauthorized());
    }
}
