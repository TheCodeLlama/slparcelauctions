package com.slparcelauctions.backend.escrow;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionEndOutcome;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.ProxyBidRepository;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auth.RefreshTokenRepository;
import com.slparcelauctions.backend.escrow.broadcast.CapturingEscrowBroadcastPublisher;
import com.slparcelauctions.backend.escrow.broadcast.EscrowBroadcastPublisher;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.VerificationCodeRepository;
import com.slparcelauctions.backend.verification.VerificationCodeType;

/**
 * Integration test: {@link EscrowService#createForEndedAuction} sets
 * {@code payoutAmt = 0} for case-3 (SL-group-owned) auctions, where earnings
 * stay in SLPA and are split into wallets by
 * {@code AgentCommissionDistributor} at payout-success. Spec §8.5, §9.6.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.auction-end.enabled=false",
        "slpa.ownership-monitor.enabled=false",
        "slpa.escrow.ownership-monitor-job.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false",
        "slpa.realty.invitation-expiry.enabled=false"
})
@Import(EscrowServiceAgentFeePayoutTest.CapturingConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EscrowServiceAgentFeePayoutTest {

    @TestConfiguration
    static class CapturingConfig {
        @Bean
        @Primary
        CapturingEscrowBroadcastPublisher capturingEscrowPublisher() {
            return new CapturingEscrowBroadcastPublisher();
        }
    }

    @Autowired EscrowService escrowService;
    @Autowired EscrowRepository escrowRepo;
    @Autowired EscrowCommissionCalculator commissionCalculator;
    @Autowired AuctionRepository auctionRepo;
    @Autowired BidRepository bidRepo;
    @Autowired ProxyBidRepository proxyBidRepo;
    @Autowired UserRepository userRepo;
    @Autowired RealtyGroupRepository realtyGroupRepo;
    @Autowired RealtyGroupSlGroupRepository realtyGroupSlGroupRepo;
    @Autowired RefreshTokenRepository refreshTokenRepo;
    @Autowired VerificationCodeRepository verificationCodeRepo;
    @Autowired NotificationRepository notificationRepo;
    @Autowired PlatformTransactionManager txManager;

    private Long seededAuctionId;
    private Long seededSellerId;
    private Long seededBidderId;
    private Long seededRealtyGroupSlGroupId;
    private Long seededRealtyGroupId;

    @AfterEach
    void cleanUp() {
        if (seededAuctionId == null) return;
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            escrowRepo.findByAuctionId(seededAuctionId).ifPresent(escrowRepo::delete);
            bidRepo.deleteAllByAuctionId(seededAuctionId);
            proxyBidRepo.deleteAllByAuctionId(seededAuctionId);
            auctionRepo.findById(seededAuctionId).ifPresent(auctionRepo::delete);
            if (seededRealtyGroupSlGroupId != null) {
                realtyGroupSlGroupRepo.findById(seededRealtyGroupSlGroupId)
                        .ifPresent(realtyGroupSlGroupRepo::delete);
            }
            if (seededRealtyGroupId != null) {
                realtyGroupRepo.findById(seededRealtyGroupId)
                        .ifPresent(realtyGroupRepo::delete);
            }
            for (Long userId : new Long[]{seededBidderId, seededSellerId}) {
                if (userId == null) continue;
                refreshTokenRepo.findAllByUserId(userId).forEach(refreshTokenRepo::delete);
                verificationCodeRepo.findByUserIdAndTypeAndUsedFalse(userId, VerificationCodeType.PLAYER)
                        .forEach(verificationCodeRepo::delete);
                verificationCodeRepo.findByUserIdAndTypeAndUsedFalse(userId, VerificationCodeType.PARCEL)
                        .forEach(verificationCodeRepo::delete);
                notificationRepo.deleteAllByUserId(userId);
                userRepo.findById(userId).ifPresent(userRepo::delete);
            }
        });
        seededAuctionId = null;
        seededSellerId = null;
        seededBidderId = null;
        seededRealtyGroupSlGroupId = null;
        seededRealtyGroupId = null;
    }

    /**
     * Case 3 (E -- SL-group-owned listing): when {@code realty_group_sl_group_id IS NOT NULL},
     * {@code createForEndedAuction} sets {@code payoutAmt = 0}. The earnings stay in SLPA
     * and are split into the listing agent's wallet + the group wallet by
     * {@code AgentCommissionDistributor} at payout-success. Spec §8.5, §9.6.
     */
    @Test
    void createForEndedAuction_case3_payoutAmtIsZero() {
        long finalBid = 10_000L;
        Auction auction = seedCase3Auction(finalBid, new BigDecimal("0.1000"));

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            Auction managed = auctionRepo.findById(auction.getId()).orElseThrow();
            escrowService.createForEndedAuction(managed, OffsetDateTime.now());
        });

        Escrow escrow = escrowRepo.findByAuctionId(auction.getId()).orElseThrow();
        assertThat(escrow.getPayoutAmt()).isZero();
        // commission is unaffected
        assertThat(escrow.getCommissionAmt()).isEqualTo(commissionCalculator.commission(finalBid));
        assertThat(escrow.getFinalBidAmount()).isEqualTo(finalBid);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Auction seedCase3Auction(long finalBid, BigDecimal agentCommissionRate) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        return tx.execute(status -> {
            UUID parcelUuid = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now();

            User seller = userRepo.save(User.builder()
                    .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("case3-seller-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Case3 Seller")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .build());

            User bidder = userRepo.save(User.builder()
                    .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("case3-bidder-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("Case3 Bidder")
                    .slAvatarUuid(UUID.randomUUID())
                    .verified(true)
                    .build());

            String suffix = UUID.randomUUID().toString().substring(0, 8);
            RealtyGroup group = realtyGroupRepo.save(RealtyGroup.builder()
                    .name("Case3 Group " + suffix)
                    .slug("case3-group-" + suffix)
                    .leaderId(seller.getId())
                    .build());

            RealtyGroupSlGroup slGroup = realtyGroupSlGroupRepo.save(RealtyGroupSlGroup.builder()
                    .realtyGroupId(group.getId())
                    .slGroupUuid(UUID.randomUUID())
                    .slGroupName("Case3 SL Group " + suffix)
                    .verified(true)
                    .verifiedAt(now)
                    .build());

            Auction auction = auctionRepo.save(Auction.builder()
                    .title("Case3 Test Listing")
                    .slParcelUuid(parcelUuid)
                    .seller(seller)
                    .listingAgent(seller)
                    .realtyGroupId(group.getId())
                    .realtyGroupSlGroupId(slGroup.getId())
                    .agentCommissionRate(agentCommissionRate)
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
                    .ownerUuid(slGroup.getSlGroupUuid())
                    .ownerType("group")
                    .parcelName("Case3 Test Parcel")
                    .regionName("Coniston")
                    .regionMaturityRating("GENERAL")
                    .areaSqm(1024)
                    .positionX(128.0).positionY(64.0).positionZ(22.0)
                    .build());
            auctionRepo.save(auction);

            seededSellerId = seller.getId();
            seededBidderId = bidder.getId();
            seededAuctionId = auction.getId();
            seededRealtyGroupSlGroupId = slGroup.getId();
            seededRealtyGroupId = group.getId();
            return auction;
        });
    }

}
