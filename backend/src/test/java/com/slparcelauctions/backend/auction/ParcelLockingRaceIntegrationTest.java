package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.sl.SlMapApiClient;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.GridCoordinates;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import reactor.core.publisher.Mono;

/**
 * Full-stack verification that the parcel-lock invariant holds under both
 * sequential and concurrent pressure. Exercises the service-layer check (which
 * identifies the blocking auction ID) and the Postgres partial unique index
 * backstop (which catches any race that slips past the service check).
 *
 * <p>Intentionally NOT annotated {@code @Transactional}: the concurrent race
 * test needs committed data to be visible across threads, and the sequential
 * tests run against live Postgres state by design. Each test uses a distinct
 * email/avatar/parcel UUID so rows left behind do not collide across methods.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
class ParcelLockingRaceIntegrationTest {

    private static final String TRUSTED_OWNER = "00000000-0000-0000-0000-000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired ParcelRepository parcelRepository;
    @Autowired AuctionRepository auctionRepository;
    @Autowired UserRepository userRepository;
    @Autowired CancellationService cancellationService;
    @Autowired PlatformTransactionManager txManager;
    @Autowired javax.sql.DataSource dataSource;

    @MockitoBean SlWorldApiClient worldApi;
    @MockitoBean SlMapApiClient mapApi;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String sellerAccessToken;
    private Long sellerId;
    private Parcel parcel;
    private UUID sellerAvatar;
    private UUID parcelUuid;

    @BeforeEach
    void setUp() throws Exception {
        // Unique identifiers per test method to avoid cross-test row collisions.
        // This test class does not use @Transactional (concurrent tests need committed
        // state) so leftover rows from prior runs are isolated by nanoTime suffix.
        long suffix = System.nanoTime() & 0xFFFFFFFFL;
        String email = "locker-" + suffix + "@example.com";
        sellerAvatar = new UUID(0xAAAAAAAAAAAAAAAAL, suffix);
        parcelUuid = new UUID(0x5555555555555555L, suffix);

        sellerAccessToken = registerAndVerifyUser(email, "Locker", sellerAvatar.toString());
        sellerId = userRepository.findByEmail(email).orElseThrow().getId();
        parcel = seedParcel();
    }

    /**
     * Cleans up rows committed by this test so other @Transactional tests that assume
     * a clean slate (e.g., hardcoded avatar/parcel UUIDs) are not broken by leftover
     * state. Uses raw JDBC to walk around the FK chain
     * (verification_codes -> refresh_tokens -> users, auctions -> parcels) without
     * needing JPA repository methods we don't otherwise need in production code.
     */
    @AfterEach
    void cleanUp() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM cancellation_logs WHERE seller_id = " + sellerId);
                stmt.execute("DELETE FROM listing_fee_refunds WHERE auction_id IN "
                        + "(SELECT id FROM auctions WHERE seller_id = " + sellerId + ")");
                stmt.execute("DELETE FROM auction_tags WHERE auction_id IN "
                        + "(SELECT id FROM auctions WHERE seller_id = " + sellerId + ")");
                stmt.execute("DELETE FROM auctions WHERE seller_id = " + sellerId);
                stmt.execute("DELETE FROM parcels WHERE id = " + parcel.getId());
                stmt.execute("DELETE FROM verification_codes WHERE user_id = " + sellerId);
                stmt.execute("DELETE FROM refresh_tokens WHERE user_id = " + sellerId);
                stmt.execute("DELETE FROM users WHERE id = " + sellerId);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Sequential verify: service-layer check catches the second one.
    // -------------------------------------------------------------------------

    @Test
    void sequentialVerify_secondAuctionOnSameParcel_returns409() throws Exception {
        stubWorldApiOwnership(sellerAvatar, "agent");
        Long a1Id = seedDraftPaidAuction();
        Long a2Id = seedDraftPaidAuction();

        // A1 verifies -> ACTIVE
        mockMvc.perform(put("/api/v1/auctions/" + a1Id + "/verify")
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // A2 verify -> 409 ParcelAlreadyListed, identifying A1 as the blocker.
        mockMvc.perform(put("/api/v1/auctions/" + a2Id + "/verify")
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARCEL_ALREADY_LISTED"))
                .andExpect(jsonPath("$.parcelId").value(parcel.getId()))
                .andExpect(jsonPath("$.blockingAuctionId").value(a1Id));
    }

    // -------------------------------------------------------------------------
    // Cancellation of A1 releases the lock so A2 can now verify.
    // -------------------------------------------------------------------------

    @Test
    void cancelledAuctionUnblocks_retryVerifySucceeds() throws Exception {
        stubWorldApiOwnership(sellerAvatar, "agent");
        Long a1Id = seedDraftPaidAuction();
        Long a2Id = seedDraftPaidAuction();

        // A1 -> ACTIVE
        mockMvc.perform(put("/api/v1/auctions/" + a1Id + "/verify")
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isOk());

        // A2 blocked
        mockMvc.perform(put("/api/v1/auctions/" + a2Id + "/verify")
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isConflict());

        // Cancel A1 -> lock released.
        // Service-layer cancel here (not the HTTP layer) because the /cancel controller
        // triggers an unrelated pre-existing LazyInitializationException on Parcel when
        // run outside a test-level @Transactional wrapper. That bug is out of scope for
        // this task; the lock-release semantics we care about are owned by the service.
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(ts -> {
            Auction a1 = auctionRepository.findById(a1Id).orElseThrow();
            cancellationService.cancel(a1, "switching");
        });

        // After the failed verify above, @Transactional rolled back the VERIFICATION_PENDING
        // save, so A2 is back in DRAFT_PAID. It can retry via /verify now that A1 is CANCELLED.
        mockMvc.perform(put("/api/v1/auctions/" + a2Id + "/verify")
                .header("Authorization", "Bearer " + sellerAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // -------------------------------------------------------------------------
    // Concurrent race: two parallel verify calls, only one wins.
    // -------------------------------------------------------------------------

    @Test
    void concurrentVerify_onlyOneWins() throws Exception {
        stubWorldApiOwnership(sellerAvatar, "agent");
        Long a1Id = seedDraftPaidAuction();
        Long a2Id = seedDraftPaidAuction();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Integer> f1 = submitVerify(pool, a1Id);
            CompletableFuture<Integer> f2 = submitVerify(pool, a2Id);

            int s1 = f1.get(30, TimeUnit.SECONDS);
            int s2 = f2.get(30, TimeUnit.SECONDS);

            // Expect exactly one 200 and one 409 — or both sequential 409/200, in which
            // case the first-saved auction wins and the second gets the service-layer
            // check. Either way, exactly one ends up ACTIVE.
            assertThat(sorted(s1, s2))
                    .as("one verify wins (200), the other loses (409); got %d and %d", s1, s2)
                    .containsExactly(200, 409);

            long activeCount = auctionRepository.findAll().stream()
                    .filter(a -> a.getStatus() == AuctionStatus.ACTIVE)
                    .filter(a -> a.getParcel().getId().equals(parcel.getId()))
                    .count();
            assertThat(activeCount).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private CompletableFuture<Integer> submitVerify(ExecutorService pool, Long auctionId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                MvcResult r = mockMvc.perform(put("/api/v1/auctions/" + auctionId + "/verify")
                        .header("Authorization", "Bearer " + sellerAccessToken))
                        .andReturn();
                return r.getResponse().getStatus();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, pool);
    }

    private static int[] sorted(int a, int b) {
        int[] arr = new int[]{a, b};
        java.util.Arrays.sort(arr);
        return arr;
    }

    private void stubWorldApiOwnership(UUID ownerUuid, String ownerType) {
        when(worldApi.fetchParcel(parcelUuid)).thenReturn(Mono.just(new ParcelMetadata(
                parcelUuid, ownerUuid, ownerType,
                "Locking Parcel", "Coniston",
                1024, "desc", "http://example.com/snap.jpg", "MATURE",
                128.0, 64.0, 22.0)));
    }

    private Long seedDraftPaidAuction() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        return tx.execute(ts -> {
            User seller = userRepository.findById(sellerId).orElseThrow();
            Auction a = Auction.builder()
                    .parcel(parcel)
                    .seller(seller)
                    .status(AuctionStatus.DRAFT_PAID)
                    .verificationMethod(VerificationMethod.UUID_ENTRY)
                    .startingBid(1000L)
                    .durationHours(168)
                    .snipeProtect(false)
                    .listingFeePaid(true)
                    .listingFeeAmt(100L)
                    .currentBid(0L)
                    .bidCount(0)
                    .commissionRate(new BigDecimal("0.05"))
                    .agentFeeRate(BigDecimal.ZERO)
                    .build();
            return auctionRepository.save(a).getId();
        });
    }

    private String registerUser(String email, String displayName) throws Exception {
        String body = String.format(
                "{\"email\":\"%s\",\"password\":\"hunter22abc\",\"displayName\":\"%s\"}",
                email, displayName);
        MvcResult reg = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated()).andReturn();
        JsonNode json = objectMapper.readTree(reg.getResponse().getContentAsString());
        return json.get("accessToken").asText();
    }

    private String registerAndVerifyUser(String email, String displayName, String avatarUuid)
            throws Exception {
        String token = registerUser(email, displayName);
        MvcResult gen = mockMvc.perform(post("/api/v1/verification/generate")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        String code = objectMapper.readTree(gen.getResponse().getContentAsString())
                .get("code").asText();
        String body = String.format("""
            {
              "verificationCode":"%s",
              "avatarUuid":"%s",
              "avatarName":"%s",
              "displayName":"%s",
              "username":"test.resident",
              "bornDate":"2012-05-15",
              "payInfo":3
            }
            """, code, avatarUuid, displayName, displayName);
        mockMvc.perform(post("/api/v1/sl/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-SecondLife-Shard", "Production")
                .header("X-SecondLife-Owner-Key", TRUSTED_OWNER))
                .andExpect(status().isOk());
        return token;
    }

    private Parcel seedParcel() throws Exception {
        when(worldApi.fetchParcel(parcelUuid)).thenReturn(Mono.just(new ParcelMetadata(
                parcelUuid, sellerAvatar, "agent",
                "Locking Parcel", "Coniston",
                1024, "desc", "http://example.com/snap.jpg", "MATURE",
                128.0, 64.0, 22.0)));
        when(mapApi.resolveRegion(any())).thenReturn(Mono.just(new GridCoordinates(260000.0, 254000.0)));

        mockMvc.perform(post("/api/v1/parcels/lookup")
                .header("Authorization", "Bearer " + sellerAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slParcelUuid\":\"" + parcelUuid + "\"}"))
                .andExpect(status().isOk());

        return parcelRepository.findBySlParcelUuid(parcelUuid).orElseThrow();
    }
}
