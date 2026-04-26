package com.slparcelauctions.backend.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.sl.SlParcelVerifyService;
import com.slparcelauctions.backend.sl.dto.SlParcelVerifyRequest;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.verification.VerificationCode;
import com.slparcelauctions.backend.verification.VerificationCodeRepository;
import com.slparcelauctions.backend.verification.VerificationCodeType;

/**
 * Vertical-slice integration tests for parcel verification notifications.
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
        "slpa.notifications.cleanup.enabled=false"
})
class ParcelVerificationNotificationIntegrationTest {

    private static final String SL_SHARD_PROD = "Production";

    @Autowired SlParcelVerifyService slParcelVerifyService;
    @Autowired AuctionRepository auctionRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;
    @Autowired VerificationCodeRepository codeRepo;
    @Autowired NotificationRepository notifRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId, auctionId, parcelId;
    private String codeStr;

    @AfterEach
    void cleanup() throws Exception {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            if (auctionId != null) {
                if (codeStr != null) {
                    codeRepo.findByCodeAndTypeAndUsedFalseAndExpiresAtAfter(
                            codeStr, VerificationCodeType.PARCEL, OffsetDateTime.now().minusYears(1))
                            .forEach(codeRepo::delete);
                }
                auctionRepo.findById(auctionId).ifPresent(auctionRepo::delete);
            }
            if (parcelId != null) parcelRepo.findById(parcelId).ifPresent(parcelRepo::delete);
        });
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                if (sellerId != null) {
                    st.execute("DELETE FROM notification WHERE user_id = " + sellerId);
                    st.execute("DELETE FROM refresh_tokens WHERE user_id = " + sellerId);
                    st.execute("DELETE FROM verification_codes WHERE user_id = " + sellerId);
                    st.execute("DELETE FROM users WHERE id = " + sellerId);
                }
            }
        }
        sellerId = auctionId = parcelId = null;
        codeStr = null;
    }

    @Test
    void verificationSuccess_publishesListingVerified() throws Exception {
        UUID sellerAvatar = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID parcelUuid = UUID.randomUUID();
        codeStr = "VRF123";

        // Seed seller, parcel, auction in VERIFICATION_PENDING
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User seller = userRepo.save(User.builder()
                    .email("verif-seller-" + UUID.randomUUID() + "@test.com")
                    .passwordHash("h")
                    .displayName("VerifSeller")
                    .slAvatarUuid(sellerAvatar)
                    .verified(true)
                    .build());
            sellerId = seller.getId();

            Parcel p = parcelRepo.save(Parcel.builder()
                    .slParcelUuid(parcelUuid)
                    .ownerUuid(sellerAvatar)
                    .ownerType("agent")
                    .regionName("VerifRegion")
                    .continentName("Sansara")
                    .areaSqm(512)
                    .maturityRating("GENERAL")
                    .verified(false)
                    .build());
            parcelId = p.getId();

            Auction a = auctionRepo.save(Auction.builder()
                    .title("Verification Test Lot")
                    .parcel(p)
                    .seller(seller)
                    .status(AuctionStatus.VERIFICATION_PENDING)
                    .verificationMethod(VerificationMethod.REZZABLE)
                    .startingBid(1000L)
                    .currentBid(0L)
                    .bidCount(0)
                    .durationHours(168)
                    .snipeProtect(false)
                    .listingFeePaid(true)
                    .consecutiveWorldApiFailures(0)
                    .commissionRate(new BigDecimal("0.05"))
                    .agentFeeRate(BigDecimal.ZERO)
                    .startsAt(OffsetDateTime.now().minusMinutes(5))
                    .endsAt(OffsetDateTime.now().plusHours(24))
                    .originalEndsAt(OffsetDateTime.now().plusHours(24))
                    .build());
            auctionId = a.getId();

            // Seed the verification code
            codeRepo.save(VerificationCode.builder()
                    .userId(seller.getId())
                    .auctionId(a.getId())
                    .type(VerificationCodeType.PARCEL)
                    .code(codeStr)
                    .used(false)
                    .expiresAt(OffsetDateTime.now().plusHours(1))
                    .build());
        });

        // Drive the SL callback — using "Production" shard and seller avatar as owner
        String trustedOwnerKey = "00000000-0000-0000-0000-000000000001";
        SlParcelVerifyRequest req = new SlParcelVerifyRequest(
                codeStr, parcelUuid, sellerAvatar,
                "VerifRegion", null, null, null, null, null, null);
        slParcelVerifyService.verify(SL_SHARD_PROD, trustedOwnerKey, req);

        var notifs = notifRepo.findAll().stream()
                .filter(n -> n.getUser().getId().equals(sellerId))
                .filter(n -> n.getCategory() == NotificationCategory.LISTING_VERIFIED)
                .toList();
        assertThat(notifs).hasSize(1);
        assertThat(notifs.get(0).getData().get("auctionId")).isNotNull();
    }
}
