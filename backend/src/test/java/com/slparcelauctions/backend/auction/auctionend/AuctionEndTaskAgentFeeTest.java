package com.slparcelauctions.backend.auction.auctionend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.ProxyBidRepository;
import com.slparcelauctions.backend.auction.broadcast.AuctionBroadcastPublisher;
import com.slparcelauctions.backend.bot.BotMonitorLifecycleService;
import com.slparcelauctions.backend.escrow.EscrowService;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Unit tests for the {@code agent_fee_amt} snapshot logic in
 * {@link AuctionEndTask#closeOne}. Covers the four zero-fee guards (null group,
 * dissolved group, null rate, zero rate), the happy path with floor rounding, and
 * the non-SOLD outcome short-circuits.
 */
@ExtendWith(MockitoExtension.class)
class AuctionEndTaskAgentFeeTest {

    @Mock AuctionRepository auctionRepo;
    @Mock ProxyBidRepository proxyBidRepo;
    @Mock BidRepository bidRepo;
    @Mock UserRepository userRepo;
    @Mock AuctionBroadcastPublisher publisher;
    @Mock EscrowService escrowService;
    @Mock BotMonitorLifecycleService monitorLifecycle;
    @Mock NotificationPublisher notificationPublisher;
    @Mock RealtyGroupRepository realtyGroupRepo;

    Clock fixed;
    AuctionEndTask task;
    User defaultSeller;
    User defaultWinner;

    @BeforeEach
    void setUp() {
        fixed = Clock.fixed(Instant.parse("2026-04-20T12:00:00Z"), ZoneOffset.UTC);
        task = new AuctionEndTask(auctionRepo, proxyBidRepo, bidRepo, userRepo, publisher,
                escrowService, monitorLifecycle, notificationPublisher, realtyGroupRepo, fixed);
        lenient().when(bidRepo.findDistinctBidderUserIdsByAuctionId(anyLong()))
                .thenReturn(List.of());
        defaultSeller = User.builder()
                .username("seller-" + java.util.UUID.randomUUID().toString().substring(0, 6))
                .id(1L)
                .displayName("Seller")
                .build();
        defaultWinner = User.builder()
                .username("winner-" + java.util.UUID.randomUUID().toString().substring(0, 6))
                .id(7L)
                .displayName("Winner")
                .build();
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // -------------------------------------------------------------------------
    // Helper builders
    // -------------------------------------------------------------------------

    /** Minimal SOLD-eligible auction fixture wired to the given repo stub. */
    private Auction soldAuction(long id, long currentBid, Long realtyGroupId, BigDecimal agentFeeRate) {
        Auction a = Auction.builder()
                .seller(defaultSeller)
                .title("Test parcel")
                .id(id)
                .status(AuctionStatus.ACTIVE)
                .endsAt(OffsetDateTime.now(fixed).minusSeconds(1))
                .startingBid(100L)
                .currentBid(currentBid)
                .currentBidderId(defaultWinner.getId())
                .reservePrice(null)
                .bidCount(1)
                .realtyGroupId(realtyGroupId)
                .agentFeeRate(agentFeeRate)
                .build();
        when(auctionRepo.findByIdForUpdate(id)).thenReturn(Optional.of(a));
        when(userRepo.findById(defaultWinner.getId())).thenReturn(Optional.of(defaultWinner));
        return a;
    }

    /** Minimal active group (not dissolved). */
    private RealtyGroup activeGroup() {
        return RealtyGroup.builder()
                .name("Test Realty")
                .slug("test-realty")
                .leaderId(1L)
                .agentFeeRate(BigDecimal.ZERO)
                .build();
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void sold_with_2pct_rate_snapshots_agent_fee_amt() {
        long bid = 1000L;
        Auction auction = soldAuction(501L, bid, 10L, new BigDecimal("0.02"));
        RealtyGroup group = activeGroup();
        when(realtyGroupRepo.findById(10L)).thenReturn(Optional.of(group));

        task.closeOne(501L);

        assertThat(auction.getEndOutcome()).isEqualTo(AuctionEndOutcome.SOLD);
        assertThat(auction.getAgentFeeAmt()).isEqualTo(20L);  // 1000 * 0.02 = 20
    }

    @Test
    void floor_rounding_drops_fraction() {
        // 1000 * 0.0333 = 33.3 — floor gives 33
        Auction auction = soldAuction(502L, 1000L, 10L, new BigDecimal("0.0333"));
        RealtyGroup group = activeGroup();
        when(realtyGroupRepo.findById(10L)).thenReturn(Optional.of(group));

        task.closeOne(502L);

        assertThat(auction.getEndOutcome()).isEqualTo(AuctionEndOutcome.SOLD);
        assertThat(auction.getAgentFeeAmt()).isEqualTo(33L);
    }

    // -------------------------------------------------------------------------
    // Zero-fee guards
    // -------------------------------------------------------------------------

    @Test
    void sold_with_dissolved_group_snapshots_zero() {
        Auction auction = soldAuction(503L, 1000L, 10L, new BigDecimal("0.02"));
        RealtyGroup dissolved = activeGroup();
        dissolved.setDissolvedAt(OffsetDateTime.now(fixed).minusDays(1));
        when(realtyGroupRepo.findById(10L)).thenReturn(Optional.of(dissolved));

        task.closeOne(503L);

        assertThat(auction.getEndOutcome()).isEqualTo(AuctionEndOutcome.SOLD);
        assertThat(auction.getAgentFeeAmt()).isEqualTo(0L);
    }

    @Test
    void sold_with_missing_group_snapshots_zero() {
        // Group row has been hard-deleted (defensive path)
        Auction auction = soldAuction(504L, 1000L, 10L, new BigDecimal("0.02"));
        when(realtyGroupRepo.findById(10L)).thenReturn(Optional.empty());

        task.closeOne(504L);

        assertThat(auction.getEndOutcome()).isEqualTo(AuctionEndOutcome.SOLD);
        assertThat(auction.getAgentFeeAmt()).isEqualTo(0L);
    }

    @Test
    void sold_with_zero_rate_snapshots_zero() {
        Auction auction = soldAuction(505L, 1000L, 10L, new BigDecimal("0.0000"));
        RealtyGroup group = activeGroup();
        when(realtyGroupRepo.findById(10L)).thenReturn(Optional.of(group));

        task.closeOne(505L);

        assertThat(auction.getEndOutcome()).isEqualTo(AuctionEndOutcome.SOLD);
        assertThat(auction.getAgentFeeAmt()).isEqualTo(0L);
    }

    // -------------------------------------------------------------------------
    // Individual listing (no realty group) — guard short-circuits
    // -------------------------------------------------------------------------

    @Test
    void sold_individual_listing_leaves_agent_fee_null() {
        // realtyGroupId == null — the if-guard in closeOne short-circuits before
        // computeAgentFeeAmt runs; agentFeeAmt is never set.
        Auction auction = soldAuction(506L, 1000L, null, new BigDecimal("0.0000"));

        task.closeOne(506L);

        assertThat(auction.getEndOutcome()).isEqualTo(AuctionEndOutcome.SOLD);
        assertThat(auction.getAgentFeeAmt()).isNull();
    }

    // -------------------------------------------------------------------------
    // Non-SOLD outcomes — SOLD branch entirely skipped
    // -------------------------------------------------------------------------

    @Test
    void no_bids_leaves_agent_fee_null() {
        Auction auction = Auction.builder()
                .seller(defaultSeller)
                .title("Test parcel")
                .id(507L)
                .status(AuctionStatus.ACTIVE)
                .endsAt(OffsetDateTime.now(fixed).minusSeconds(1))
                .startingBid(100L)
                .currentBid(0L)
                .realtyGroupId(10L)
                .agentFeeRate(new BigDecimal("0.02"))
                .bidCount(0)
                .build();
        when(auctionRepo.findByIdForUpdate(507L)).thenReturn(Optional.of(auction));

        task.closeOne(507L);

        assertThat(auction.getEndOutcome()).isEqualTo(AuctionEndOutcome.NO_BIDS);
        assertThat(auction.getAgentFeeAmt()).isNull();
    }

    @Test
    void reserve_not_met_leaves_agent_fee_null() {
        Auction auction = Auction.builder()
                .seller(defaultSeller)
                .title("Test parcel")
                .id(508L)
                .status(AuctionStatus.ACTIVE)
                .endsAt(OffsetDateTime.now(fixed).minusSeconds(1))
                .startingBid(100L)
                .currentBid(750L)
                .currentBidderId(defaultWinner.getId())
                .reservePrice(1000L)
                .realtyGroupId(10L)
                .agentFeeRate(new BigDecimal("0.02"))
                .bidCount(1)
                .build();
        when(auctionRepo.findByIdForUpdate(508L)).thenReturn(Optional.of(auction));

        task.closeOne(508L);

        assertThat(auction.getEndOutcome()).isEqualTo(AuctionEndOutcome.RESERVE_NOT_MET);
        assertThat(auction.getAgentFeeAmt()).isNull();
    }
}
