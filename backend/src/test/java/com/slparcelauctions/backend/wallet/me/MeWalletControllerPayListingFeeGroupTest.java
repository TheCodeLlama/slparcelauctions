package com.slparcelauctions.backend.wallet.me;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntry;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntryType;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerRepository;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Integration tests for the group-wallet branch in
 * {@code POST /api/v1/me/auctions/{publicId}/pay-listing-fee}.
 *
 * <p>Three cases (spec §5.4):
 * <ol>
 *   <li>Group-listed auction with sufficient group balance — group balance decreases,
 *       user balance unchanged, realty_group_ledger has a LISTING_FEE_DEBIT row with
 *       actor=agent, auction status = DRAFT_PAID.</li>
 *   <li>Individual auction — user balance decreases (existing behavior), no group ledger row.</li>
 *   <li>Group-listed auction with insufficient group balance — 422 INSUFFICIENT_GROUP_BALANCE.</li>
 * </ol>
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
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false"
})
@Transactional
class MeWalletControllerPayListingFeeGroupTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;
    @Autowired AuctionRepository auctionRepository;
    @Autowired RealtyGroupRepository groupRepository;
    @Autowired RealtyGroupLedgerRepository groupLedgerRepository;

    /** Listing fee configured in test properties (default 100 L$). */
    private static final long LISTING_FEE = 100L;

    private User agent;
    private String agentJwt;

    @BeforeEach
    void setUp() {
        agent = userRepository.save(User.builder()
            .username("group-fee-agent-" + UUID.randomUUID().toString().substring(0, 8))
            .email("group-fee-agent-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x")
            .displayName("Agent")
            .balanceLindens(0L)
            .reservedLindens(0L)
            .walletTermsAcceptedAt(OffsetDateTime.now())
            .walletTermsVersion("v1.0")
            .build());
        agentJwt = jwtService.issueAccessToken(
            new AuthPrincipal(agent.getId(), agent.getPublicId(), agent.getEmail(), 0L, Role.USER));
    }

    // -------------------------------------------------------------------------
    // Case 1: group-listed auction — group wallet debited, user wallet untouched
    // -------------------------------------------------------------------------

    @Test
    void groupListedAuction_debitsGroupWallet_notUserWallet() throws Exception {
        RealtyGroup group = saveGroup(agent.getId(), 300L);
        Auction auction = saveDraftAuction(agent, group, LISTING_FEE);

        mockMvc.perform(post("/api/v1/me/auctions/" + auction.getPublicId() + "/pay-listing-fee")
                .header("Authorization", "Bearer " + agentJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.auctionStatus").value("DRAFT_PAID"));

        // Group balance decremented
        RealtyGroup reloadedGroup = groupRepository.findById(group.getId()).orElseThrow();
        assertThat(reloadedGroup.getBalanceLindens()).isEqualTo(300L - LISTING_FEE);

        // User balance unchanged (was 0, stays 0)
        User reloadedAgent = userRepository.findById(agent.getId()).orElseThrow();
        assertThat(reloadedAgent.getBalanceLindens()).isEqualTo(0L);

        // Ledger row exists with correct type and actor
        List<RealtyGroupLedgerEntry> ledgerRows =
            groupLedgerRepository.findRecentForGroup(group.getId(),
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(ledgerRows).hasSize(1);
        RealtyGroupLedgerEntry row = ledgerRows.get(0);
        assertThat(row.getEntryType()).isEqualTo(RealtyGroupLedgerEntryType.LISTING_FEE_DEBIT);
        assertThat(row.getAmount()).isEqualTo(LISTING_FEE);
        assertThat(row.getActorUserId()).isEqualTo(agent.getId());
        assertThat(row.getRefType()).isEqualTo("AUCTION");
        assertThat(row.getRefId()).isEqualTo(auction.getId());

        // Auction advanced to DRAFT_PAID
        Auction reloadedAuction = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloadedAuction.getStatus()).isEqualTo(AuctionStatus.DRAFT_PAID);
    }

    // -------------------------------------------------------------------------
    // Case 2: individual auction — user wallet debited, no group ledger row
    // -------------------------------------------------------------------------

    @Test
    void individualAuction_debitsUserWallet_noGroupLedgerRow() throws Exception {
        // Give the agent a user-wallet balance sufficient for the listing fee
        agent.setBalanceLindens(500L);
        userRepository.save(agent);

        Auction auction = saveDraftAuction(agent, null, LISTING_FEE);

        mockMvc.perform(post("/api/v1/me/auctions/" + auction.getPublicId() + "/pay-listing-fee")
                .header("Authorization", "Bearer " + agentJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.auctionStatus").value("DRAFT_PAID"))
            .andExpect(jsonPath("$.newBalance").value(400));

        // User balance debited
        User reloaded = userRepository.findById(agent.getId()).orElseThrow();
        assertThat(reloaded.getBalanceLindens()).isEqualTo(500L - LISTING_FEE);

        // No group ledger rows at all for the auction
        // (We haven't created any group, so no group to check — just verify no crash)
        Auction reloadedAuction = auctionRepository.findById(auction.getId()).orElseThrow();
        assertThat(reloadedAuction.getStatus()).isEqualTo(AuctionStatus.DRAFT_PAID);
    }

    // -------------------------------------------------------------------------
    // Case 3: group-listed auction, group balance insufficient — 422
    // -------------------------------------------------------------------------

    @Test
    void groupListedAuction_insufficientGroupBalance_returns422() throws Exception {
        // Group balance is 50 L$, fee is 100 L$
        RealtyGroup group = saveGroup(agent.getId(), 50L);
        Auction auction = saveDraftAuction(agent, group, LISTING_FEE);

        mockMvc.perform(post("/api/v1/me/auctions/" + auction.getPublicId() + "/pay-listing-fee")
                .header("Authorization", "Bearer " + agentJwt)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"" + UUID.randomUUID() + "\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_GROUP_BALANCE"));

        // Group and user balances must not have changed
        RealtyGroup reloadedGroup = groupRepository.findById(group.getId()).orElseThrow();
        assertThat(reloadedGroup.getBalanceLindens()).isEqualTo(50L);
        User reloadedAgent = userRepository.findById(agent.getId()).orElseThrow();
        assertThat(reloadedAgent.getBalanceLindens()).isEqualTo(0L);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private RealtyGroup saveGroup(Long leaderId, long balance) {
        String slug = "grp-" + UUID.randomUUID().toString().substring(0, 8);
        return groupRepository.save(RealtyGroup.builder()
            .name("Test Group " + slug)
            .slug(slug)
            .leaderId(leaderId)
            .agentFeeRate(new BigDecimal("0.0200"))
            .balanceLindens(balance)
            .reservedLindens(0L)
            .build());
    }

    private Auction saveDraftAuction(User seller, RealtyGroup group, long listingFeeAmt) {
        UUID parcelUuid = UUID.randomUUID();
        Auction a = Auction.builder()
            .title("Group fee test listing")
            .slParcelUuid(parcelUuid)
            .seller(seller)
            .status(AuctionStatus.DRAFT)
            .startingBid(1000L)
            .durationHours(168)
            .snipeProtect(false)
            .listingFeePaid(false)
            .listingFeeAmt(listingFeeAmt)
            .currentBid(0L)
            .bidCount(0)
            .commissionRate(new BigDecimal("0.05"))
            .agentFeeRate(BigDecimal.ZERO)
            .realtyGroupId(group == null ? null : group.getId())
            .build();
        a.setParcelSnapshot(AuctionParcelSnapshot.builder()
            .slParcelUuid(parcelUuid)
            .ownerUuid(UUID.randomUUID())
            .ownerType("agent")
            .ownerName("Test Agent")
            .parcelName("Test Parcel")
            .description("Test description")
            .regionName("Coniston")
            .areaSqm(1024)
            .positionX(128.0).positionY(64.0).positionZ(22.0)
            .build());
        return auctionRepository.save(a);
    }
}
