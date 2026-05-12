package com.slparcelauctions.backend.escrow.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.ProxyBidRepository;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auth.RefreshTokenRepository;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowCommissionCalculator;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.escrow.EscrowState;
import com.slparcelauctions.backend.escrow.EscrowTransactionRepository;
import com.slparcelauctions.backend.escrow.broadcast.CapturingEscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.broadcast.EscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.command.dto.PayoutResultRequest;
import com.slparcelauctions.backend.escrow.terminal.Terminal;
import com.slparcelauctions.backend.escrow.terminal.TerminalRepository;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.VerificationCodeRepository;
import com.slparcelauctions.backend.verification.VerificationCodeType;

/**
 * Integration test: verifies that a successful PAYOUT callback on a group-listed
 * auction credits group wallet and listing-agent user wallet according to
 * {@code agent_fee_amt} and {@code agent_fee_split}. Spec §7.2.
 *
 * <p>Fixture: group (balance=0) + listing agent + auction with
 * {@code agentFeeAmt=50, agentFeeSplit=0.5} → group should receive 25,
 * agent should receive 25.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.auction-end.enabled=false",
        "slpa.ownership-monitor.enabled=false",
        "slpa.escrow.ownership-monitor-job.enabled=true",
        "slpa.escrow.ownership-monitor-job.fixed-delay=PT24H",
        "slpa.escrow.command-dispatcher-job.enabled=true",
        "slpa.escrow.command-dispatcher-job.fixed-delay=PT24H",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false",
        "slpa.realty.invitation-expiry.enabled=false"
})
@Import(TerminalCommandServicePayoutDistributesAgentFeeTest.CapturingConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TerminalCommandServicePayoutDistributesAgentFeeTest {

    private static final String TERMINAL_ID = "terminal-agentfee-" + UUID.randomUUID();
    private static final String HTTP_IN_URL = "https://sim-agentfee.agni.lindenlab.com:12043/cap/abc";
    private static final String REGION_NAME = "AgentFeeRegion";
    private static final String SHARED_SECRET = "dev-escrow-secret-do-not-use-in-prod";

    @TestConfiguration
    static class CapturingConfig {
        @Bean
        @Primary
        CapturingEscrowBroadcastPublisher capturingEscrowPublisher() {
            return new CapturingEscrowBroadcastPublisher();
        }
    }

    @MockitoBean SlWorldApiClient worldApi;
    @MockitoBean TerminalHttpClient terminalHttp;

    @Autowired TerminalCommandService terminalCommandService;
    @Autowired TerminalCommandRepository cmdRepo;
    @Autowired TerminalRepository terminalRepo;
    @Autowired EscrowRepository escrowRepo;
    @Autowired EscrowTransactionRepository escrowTxRepo;
    @Autowired EscrowCommissionCalculator commissionCalculator;
    @Autowired AuctionRepository auctionRepo;
    @Autowired BidRepository bidRepo;
    @Autowired ProxyBidRepository proxyBidRepo;
    @Autowired UserRepository userRepo;
    @Autowired RealtyGroupRepository groupRepo;
    @Autowired com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerRepository groupLedgerRepo;
    @Autowired com.slparcelauctions.backend.wallet.UserLedgerRepository userLedgerRepo;
    @Autowired RefreshTokenRepository refreshTokenRepo;
    @Autowired VerificationCodeRepository verificationCodeRepo;
    @Autowired NotificationRepository notificationRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired CapturingEscrowBroadcastPublisher capturingEscrowPublisher;

    private Long seededAuctionId;
    private Long seededEscrowId;
    private Long seededSellerId;
    private Long seededBidderId;
    private Long seededAgentId;
    private Long seededGroupId;

    @AfterEach
    void cleanUp() {
        if (seededAuctionId == null) return;
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            // 1. Terminal commands referencing the escrow
            cmdRepo.findAll().stream()
                    .filter(c -> c.getEscrowId() != null && c.getEscrowId().equals(seededEscrowId))
                    .forEach(cmdRepo::delete);
            // 2. Escrow ledger rows
            escrowTxRepo.findByEscrowIdOrderByCreatedAtAsc(seededEscrowId)
                    .forEach(escrowTxRepo::delete);
            // 3. Escrow row
            escrowRepo.findByAuctionId(seededAuctionId).ifPresent(escrowRepo::delete);
            // 4. Auction bids and the auction itself
            bidRepo.deleteAllByAuctionId(seededAuctionId);
            proxyBidRepo.deleteAllByAuctionId(seededAuctionId);
            auctionRepo.findById(seededAuctionId).ifPresent(auctionRepo::delete);
            // 5. Group ledger rows (agent_fee_credit), then the group
            if (seededGroupId != null) {
                groupLedgerRepo.findAll().stream()
                        .filter(e -> seededGroupId.equals(e.getGroupId()))
                        .forEach(groupLedgerRepo::delete);
                groupRepo.findById(seededGroupId).ifPresent(groupRepo::delete);
            }
            // 6. User ledger rows (agent_fee_credit to agent), notifications,
            //    refresh tokens, verification codes, then the users themselves.
            for (Long userId : new Long[]{seededBidderId, seededSellerId, seededAgentId}) {
                if (userId == null) continue;
                userLedgerRepo.findTop50ByUserIdOrderByCreatedAtDesc(userId)
                        .forEach(userLedgerRepo::delete);
                refreshTokenRepo.findAllByUserId(userId).forEach(refreshTokenRepo::delete);
                verificationCodeRepo.findByUserIdAndTypeAndUsedFalse(userId,
                        VerificationCodeType.PLAYER).forEach(verificationCodeRepo::delete);
                verificationCodeRepo.findByUserIdAndTypeAndUsedFalse(userId,
                        VerificationCodeType.PARCEL).forEach(verificationCodeRepo::delete);
                notificationRepo.deleteAllByUserId(userId);
                userRepo.findById(userId).ifPresent(userRepo::delete);
            }
            terminalRepo.findById(TERMINAL_ID).ifPresent(terminalRepo::delete);
        });
        seededAuctionId = null;
        seededEscrowId = null;
        seededSellerId = null;
        seededBidderId = null;
        seededAgentId = null;
        seededGroupId = null;
    }

    @Test
    void successfulPayoutCallback_creditsGroupAndAgentWallets_by50_50Split() {
        long agentFeeAmt = 50L;
        BigDecimal agentFeeSplit = new BigDecimal("0.5"); // 50% group, 50% agent
        seedTransferPendingWithGroupAuction(agentFeeAmt, agentFeeSplit);

        // Simulate the terminal's payout-result callback: success.
        String idempotencyKey = "ESC-" + seededEscrowId + "-PAYOUT-1";
        String slTxn = "sl-agentfee-txn-" + UUID.randomUUID();
        terminalCommandService.applyCallback(new PayoutResultRequest(
                idempotencyKey, true, slTxn, null,
                TERMINAL_ID, SHARED_SECRET));

        // Reload group and agent from DB.
        RealtyGroup group = groupRepo.findById(seededGroupId).orElseThrow();
        User agent = userRepo.findById(seededAgentId).orElseThrow();

        // floor(50 * 0.5) = 25 to group, 50 - 25 = 25 to agent.
        assertThat(group.getBalanceLindens()).isEqualTo(25L);
        assertThat(agent.getBalanceLindens()).isEqualTo(25L);
    }

    @Test
    void successfulPayoutCallback_noAgentFee_doesNotCreditGroupOrAgent() {
        // agentFeeAmt=null → no distribution; this mirrors pre-D individual listings.
        seedTransferPendingWithGroupAuction(null, null);

        String idempotencyKey = "ESC-" + seededEscrowId + "-PAYOUT-1";
        String slTxn = "sl-noagentfee-txn-" + UUID.randomUUID();
        terminalCommandService.applyCallback(new PayoutResultRequest(
                idempotencyKey, true, slTxn, null,
                TERMINAL_ID, SHARED_SECRET));

        RealtyGroup group = groupRepo.findById(seededGroupId).orElseThrow();
        User agent = userRepo.findById(seededAgentId).orElseThrow();
        assertThat(group.getBalanceLindens()).isEqualTo(0L);
        assertThat(agent.getBalanceLindens()).isEqualTo(0L);
    }

    // -------------------------------------------------------------------------
    // Helpers / seeding
    // -------------------------------------------------------------------------

    /**
     * Seeds: realty group (balanceLindens=0) + listing agent + seller (also the agent)
     * + bidder + auction with realtyGroupId + agentFeeAmt + agentFeeSplit +
     * escrow in TRANSFER_PENDING + PAYOUT terminal command.
     */
    private void seedTransferPendingWithGroupAuction(Long agentFeeAmt, BigDecimal agentFeeSplit) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            UUID sellerAvatar = UUID.randomUUID();
            UUID winnerAvatar = UUID.randomUUID();
            UUID parcelUuid = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now();
            long finalBid = 5_000L;

            // The listing agent is also the seller in this fixture (Case 1).
            User agent = userRepo.save(User.builder()
                    .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("agentfee-agent-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("AgentFee Agent")
                    .slAvatarUuid(sellerAvatar)
                    .balanceLindens(0L)
                    .verified(true)
                    .build());

            User bidder = userRepo.save(User.builder()
                    .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("agentfee-bidder-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("AgentFee Bidder")
                    .slAvatarUuid(winnerAvatar)
                    .balanceLindens(0L)
                    .verified(true)
                    .build());

            RealtyGroup group = groupRepo.save(RealtyGroup.builder()
                    .name("AgentFee Group " + UUID.randomUUID().toString().substring(0, 8))
                    .slug("agentfee-group-" + UUID.randomUUID().toString().substring(0, 8))
                    .leaderId(agent.getId())
                    .agentFeeRate(agentFeeAmt != null ? new BigDecimal("0.02") : BigDecimal.ZERO)
                    .agentFeeSplit(agentFeeSplit != null ? agentFeeSplit : BigDecimal.ZERO)
                    .build());

            long payoutAmt = commissionCalculator.payout(finalBid)
                    - (agentFeeAmt != null ? agentFeeAmt : 0L);

            Auction auction = auctionRepo.save(Auction.builder()
                    .title("AgentFee Test Listing")
                    .slParcelUuid(parcelUuid)
                    .seller(agent)
                    .listingAgent(agent)
                    .realtyGroupId(group.getId())
                    .status(AuctionStatus.ENDED)
                    .verificationMethod(VerificationMethod.UUID_ENTRY)
                    .verificationTier(VerificationTier.SCRIPT)
                    .startingBid(500L)
                    .reservePrice(1_000L)
                    .currentBid(finalBid)
                    .bidCount(2)
                    .durationHours(168)
                    .snipeProtect(false)
                    .listingFeePaid(true)
                    .consecutiveWorldApiFailures(0)
                    .commissionRate(new BigDecimal("0.05"))
                    .agentFeeRate(agentFeeAmt != null ? new BigDecimal("0.01") : BigDecimal.ZERO)
                    .agentFeeSplit(agentFeeSplit != null ? agentFeeSplit : BigDecimal.ZERO)
                    .agentFeeAmt(agentFeeAmt)
                    .startsAt(now.minusHours(3))
                    .endsAt(now.minusHours(1))
                    .originalEndsAt(now.minusHours(1))
                    .endedAt(now.minusHours(1))
                    .endOutcome(AuctionEndOutcome.SOLD)
                    .winnerUserId(bidder.getId())
                    .finalBidAmount(finalBid)
                    .build());
            auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                    .slParcelUuid(parcelUuid)
                    .ownerUuid(winnerAvatar)
                    .ownerType("agent")
                    .parcelName("AgentFee Test Parcel")
                    .regionName(REGION_NAME)
                    .regionMaturityRating("GENERAL")
                    .areaSqm(1024)
                    .positionX(128.0).positionY(64.0).positionZ(22.0)
                    .build());
            auctionRepo.save(auction);

            Escrow escrow = escrowRepo.save(Escrow.builder()
                    .auction(auction)
                    .state(EscrowState.TRANSFER_PENDING)
                    .finalBidAmount(finalBid)
                    .commissionAmt(commissionCalculator.commission(finalBid))
                    .payoutAmt(payoutAmt)
                    .paymentDeadline(now.minusMinutes(30))
                    .transferDeadline(now.plusHours(71))
                    .fundedAt(now.minusMinutes(30))
                    .transferConfirmedAt(now.minusMinutes(10))
                    .consecutiveWorldApiFailures(0)
                    .build());

            terminalRepo.save(Terminal.builder()
                    .terminalId(TERMINAL_ID)
                    .httpInUrl(HTTP_IN_URL)
                    .regionName(REGION_NAME)
                    .active(true)
                    .lastSeenAt(now)
                    .build());

            // Queue the PAYOUT command (mirrors queuePayout).
            String idempotencyKey = "ESC-" + escrow.getId() + "-PAYOUT-1";
            cmdRepo.save(TerminalCommand.builder()
                    .escrowId(escrow.getId())
                    .action(TerminalCommandAction.PAYOUT)
                    .purpose(TerminalCommandPurpose.AUCTION_ESCROW)
                    .recipientUuid(sellerAvatar.toString())
                    .amount(payoutAmt)
                    .status(TerminalCommandStatus.IN_FLIGHT)
                    .idempotencyKey(idempotencyKey)
                    .terminalId(TERMINAL_ID)
                    .nextAttemptAt(now)
                    .attemptCount(1)
                    .requiresManualReview(false)
                    .build());

            seededSellerId = agent.getId();   // seller = agent in this fixture
            seededAgentId = agent.getId();
            seededBidderId = bidder.getId();
            seededGroupId = group.getId();
            seededAuctionId = auction.getId();
            seededEscrowId = escrow.getId();
        });
    }
}
