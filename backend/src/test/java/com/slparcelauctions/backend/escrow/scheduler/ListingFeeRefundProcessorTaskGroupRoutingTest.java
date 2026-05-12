package com.slparcelauctions.backend.escrow.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.ListingFeeRefund;
import com.slparcelauctions.backend.auction.ListingFeeRefundRepository;
import com.slparcelauctions.backend.auction.RefundStatus;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntry;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntryType;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.UserLedgerRepository;

/**
 * Integration tests for the group-wallet routing in
 * {@link ListingFeeRefundProcessorTask#queueOne}.
 *
 * <p>Three cases (spec §8.1):
 * <ol>
 *   <li>D-era group listing — {@code realty_group_ledger.LISTING_FEE_DEBIT} exists → refund
 *       credits the group wallet; seller's user wallet is unchanged.</li>
 *   <li>C-era group listing — auction has {@code realty_group_id} set but the original
 *       listing-fee debit was from the user wallet (no group ledger row) → refund credits
 *       the user wallet; group balance unchanged.</li>
 *   <li>Individual listing — no group at all → refund credits the user wallet.</li>
 * </ol>
 *
 * <p>Note: {@link ListingFeeRefundProcessorTask#queueOne} runs with
 * {@code Propagation.REQUIRES_NEW}; this test class is intentionally NOT
 * {@code @Transactional} so the inserts are committed and visible to the
 * nested transaction. Cleanup is performed in {@link #cleanup()}.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "slpa.wallet.enforcement-enabled=true",
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.escrow.ownership-monitor-job.enabled=false",
    "slpa.escrow.timeout-job.enabled=false",
    "slpa.escrow.command-dispatcher-job.enabled=false",
    "slpa.escrow.listing-fee-refund-job.enabled=false",
    "slpa.review.scheduler.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false"
})
class ListingFeeRefundProcessorTaskGroupRoutingTest {

    @Autowired ListingFeeRefundProcessorTask processorTask;
    @Autowired ListingFeeRefundRepository refundRepository;
    @Autowired AuctionRepository auctionRepository;
    @Autowired UserRepository userRepository;
    @Autowired RealtyGroupRepository groupRepository;
    @Autowired RealtyGroupLedgerRepository groupLedgerRepository;
    @Autowired UserLedgerRepository userLedgerRepository;

    /** IDs of rows created by each test — deleted in @AfterEach. */
    private Long refundId;
    private Long auctionId;
    private Long groupId;
    private Long userId;
    private Long groupLedgerEntryId;

    @AfterEach
    void cleanup() {
        // Delete in reverse FK order:
        // 1. listing_fee_refunds (references auctions)
        if (refundId != null)           { refundRepository.deleteById(refundId); }
        // 2. realty_group_ledger rows for this group (processor may have added a REFUND row)
        if (groupId != null)            {
            groupLedgerRepository.findRecentForGroup(
                groupId, org.springframework.data.domain.PageRequest.of(0, 100))
                .forEach(e -> groupLedgerRepository.deleteById(e.getId()));
        }
        // 3. The seeded LISTING_FEE_DEBIT row (may already be gone via above; safe to retry)
        if (groupLedgerEntryId != null) {
            if (groupLedgerRepository.existsById(groupLedgerEntryId)) {
                groupLedgerRepository.deleteById(groupLedgerEntryId);
            }
        }
        // 4. user_ledger rows for this user (processor may have added a LISTING_FEE_REFUND row)
        if (userId != null) {
            userLedgerRepository.findByUserIdOrderByCreatedAtDesc(
                userId, org.springframework.data.domain.PageRequest.of(0, 100))
                .forEach(e -> userLedgerRepository.deleteById(e.getId()));
        }
        // 5. auctions (references users; must come before user delete)
        if (auctionId != null)          { auctionRepository.deleteById(auctionId); }
        // 6. realty_groups (no more ledger rows after step 2)
        if (groupId != null)            {
            if (groupRepository.existsById(groupId)) {
                groupRepository.deleteById(groupId);
            }
        }
        // 7. users (no more ledger rows after step 4)
        if (userId != null)             { userRepository.deleteById(userId); }
    }

    // -------------------------------------------------------------------------
    // Case 1: D-era group listing → group wallet route
    // -------------------------------------------------------------------------

    @Test
    void dEraGroupListing_refundRoutesToGroupWallet() {
        User seller = saveUser(200L);
        RealtyGroup group = saveGroup(seller.getId(), 0L);
        Auction auction = saveAuction(seller, group.getId());

        // D-era: a LISTING_FEE_DEBIT row exists in realty_group_ledger
        RealtyGroupLedgerEntry debitEntry = saveGroupLedgerDebit(group, auction, 100L);
        groupLedgerEntryId = debitEntry.getId();

        ListingFeeRefund refund = saveRefund(auction, 100L);

        processorTask.queueOne(refund.getId());

        // Group balance increased by refund amount
        RealtyGroup reloadedGroup = groupRepository.findById(group.getId()).orElseThrow();
        assertThat(reloadedGroup.getBalanceLindens()).isEqualTo(100L);

        // Seller's user wallet unchanged
        User reloadedSeller = userRepository.findById(seller.getId()).orElseThrow();
        assertThat(reloadedSeller.getBalanceLindens()).isEqualTo(200L);

        // Refund row marked PROCESSED
        ListingFeeRefund reloadedRefund = refundRepository.findById(refund.getId()).orElseThrow();
        assertThat(reloadedRefund.getStatus()).isEqualTo(RefundStatus.PROCESSED);
        assertThat(reloadedRefund.getProcessedAt()).isNotNull();

        // A new LISTING_FEE_REFUND row exists in the group ledger
        List<RealtyGroupLedgerEntry> ledger =
            groupLedgerRepository.findRecentForGroup(group.getId(),
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(ledger).anyMatch(e ->
            e.getEntryType() == RealtyGroupLedgerEntryType.LISTING_FEE_REFUND
            && e.getAmount() == 100L
            && refund.getId().equals(e.getRefId()));
    }

    // -------------------------------------------------------------------------
    // Case 2: C-era group listing → user wallet route
    // -------------------------------------------------------------------------

    @Test
    void cEraGroupListing_refundRoutesToUserWallet() {
        User seller = saveUser(0L);
        RealtyGroup group = saveGroup(seller.getId(), 500L);
        Auction auction = saveAuction(seller, group.getId());

        // C-era: auction has realty_group_id set BUT no group ledger debit row exists
        // (listing fee was paid from user_ledger before D shipped)

        ListingFeeRefund refund = saveRefund(auction, 150L);

        processorTask.queueOne(refund.getId());

        // Seller's user wallet credited
        User reloadedSeller = userRepository.findById(seller.getId()).orElseThrow();
        assertThat(reloadedSeller.getBalanceLindens()).isEqualTo(150L);

        // Group balance unchanged
        RealtyGroup reloadedGroup = groupRepository.findById(group.getId()).orElseThrow();
        assertThat(reloadedGroup.getBalanceLindens()).isEqualTo(500L);

        // Refund row marked PROCESSED
        ListingFeeRefund reloadedRefund = refundRepository.findById(refund.getId()).orElseThrow();
        assertThat(reloadedRefund.getStatus()).isEqualTo(RefundStatus.PROCESSED);
        assertThat(reloadedRefund.getProcessedAt()).isNotNull();

        // No group ledger row for this auction
        assertThat(groupLedgerRepository.findListingFeeDebitForAuction(auction.getId()))
            .isEmpty();
    }

    // -------------------------------------------------------------------------
    // Case 3: Individual listing → user wallet route
    // -------------------------------------------------------------------------

    @Test
    void individualListing_refundRoutesToUserWallet() {
        User seller = saveUser(0L);
        Auction auction = saveAuction(seller, null);

        ListingFeeRefund refund = saveRefund(auction, 75L);

        processorTask.queueOne(refund.getId());

        // Seller's user wallet credited
        User reloadedSeller = userRepository.findById(seller.getId()).orElseThrow();
        assertThat(reloadedSeller.getBalanceLindens()).isEqualTo(75L);

        // Refund row marked PROCESSED
        ListingFeeRefund reloadedRefund = refundRepository.findById(refund.getId()).orElseThrow();
        assertThat(reloadedRefund.getStatus()).isEqualTo(RefundStatus.PROCESSED);
        assertThat(reloadedRefund.getProcessedAt()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private User saveUser(long initialBalance) {
        User u = userRepository.save(User.builder()
            .username("refund-test-" + UUID.randomUUID().toString().substring(0, 8))
            .email("refund-test-" + UUID.randomUUID() + "@test.local")
            .passwordHash("x")
            .displayName("Refund Test User")
            .balanceLindens(initialBalance)
            .reservedLindens(0L)
            .walletTermsAcceptedAt(OffsetDateTime.now())
            .walletTermsVersion("v1.0")
            .build());
        userId = u.getId();
        return u;
    }

    private RealtyGroup saveGroup(Long leaderId, long balance) {
        String slug = "rfgrp-" + UUID.randomUUID().toString().substring(0, 8);
        RealtyGroup g = groupRepository.save(RealtyGroup.builder()
            .name("Refund Test Group " + slug)
            .slug(slug)
            .leaderId(leaderId)
            .agentFeeRate(BigDecimal.ZERO)
            .balanceLindens(balance)
            .reservedLindens(0L)
            .build());
        groupId = g.getId();
        return g;
    }

    private Auction saveAuction(User seller, Long groupId) {
        UUID parcelUuid = UUID.randomUUID();
        Auction a = Auction.builder()
            .title("Refund routing test listing")
            .slParcelUuid(parcelUuid)
            .seller(seller)
            .status(AuctionStatus.CANCELLED)
            .startingBid(1000L)
            .durationHours(168)
            .snipeProtect(false)
            .listingFeePaid(true)
            .listingFeeAmt(100L)
            .currentBid(0L)
            .bidCount(0)
            .commissionRate(new BigDecimal("0.05"))
            .agentFeeRate(BigDecimal.ZERO)
            .realtyGroupId(groupId)
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
        Auction saved = auctionRepository.save(a);
        auctionId = saved.getId();
        return saved;
    }

    /**
     * Simulates a D-era group wallet debit: saves a LISTING_FEE_DEBIT entry
     * directly into {@code realty_group_ledger} (bypassing the service layer
     * to avoid MANDATORY-propagation requirement in tests).
     */
    private RealtyGroupLedgerEntry saveGroupLedgerDebit(
            RealtyGroup group, Auction auction, long amount) {
        return groupLedgerRepository.save(RealtyGroupLedgerEntry.builder()
            .groupId(group.getId())
            .entryType(RealtyGroupLedgerEntryType.LISTING_FEE_DEBIT)
            .amount(amount)
            .balanceAfter(group.getBalanceLindens() - amount)
            .reservedAfter(0L)
            .refType("AUCTION")
            .refId(auction.getId())
            .actorUserId(group.getLeaderId())
            .build());
    }

    private ListingFeeRefund saveRefund(Auction auction, long amount) {
        ListingFeeRefund r = refundRepository.save(ListingFeeRefund.builder()
            .auction(auction)
            .amount(amount)
            .status(RefundStatus.PENDING)
            .build());
        refundId = r.getId();
        return r;
    }
}
