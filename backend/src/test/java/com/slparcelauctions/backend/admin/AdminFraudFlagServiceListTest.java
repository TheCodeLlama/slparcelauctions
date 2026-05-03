package com.slparcelauctions.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.admin.dto.AdminFraudFlagSummaryDto;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.fraud.FraudFlag;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;
import com.slparcelauctions.backend.auction.fraud.FraudFlagRepository;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

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
    "slpa.notifications.sl-im.cleanup.enabled=false"
})
class AdminFraudFlagServiceListTest {

    @Autowired AdminFraudFlagService service;
    @Autowired AuctionRepository auctionRepo;
    @Autowired UserRepository userRepo;
    @Autowired FraudFlagRepository fraudFlagRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId;
    private Long auctionId;
    private Long flag1Id, flag2Id, flag3Id, flag4Id, flag5Id;

    private static final PageRequest PAGE = PageRequest.of(0, 25, Sort.by(Sort.Direction.DESC, "detectedAt"));

    @BeforeEach
    void seed() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User seller = userRepo.save(User.builder()
                .email("ff-seller-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .build());
            sellerId = seller.getId();

            UUID parcelUuid = UUID.randomUUID();
            Auction auction = auctionRepo.save(Auction.builder()
                .seller(seller)
                .slParcelUuid(parcelUuid)
                .title("Suspended fraud auction")
                .status(AuctionStatus.SUSPENDED)
                .verificationTier(VerificationTier.SCRIPT)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .startingBid(100L)
                .durationHours(24)
                .endsAt(OffsetDateTime.now().plusHours(24))
                .consecutiveWorldApiFailures(0)
                .build());
            auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                .slParcelUuid(parcelUuid)
                .ownerUuid(seller.getSlAvatarUuid())
                .ownerType("agent")
                .parcelName("FF List Test Parcel")
                .regionName("Test Region")
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
            auctionRepo.save(auction);
            auctionId = auction.getId();

            // 3 open OWNERSHIP_CHANGED_TO_UNKNOWN
            FraudFlag f1 = fraudFlagRepo.save(FraudFlag.builder()
                .auction(auction)
                .slParcelUuid(parcelUuid)
                .reason(FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN)
                .detectedAt(OffsetDateTime.now().minusHours(3))
                .resolved(false)
                .build());
            flag1Id = f1.getId();

            FraudFlag f2 = fraudFlagRepo.save(FraudFlag.builder()
                .auction(auction)
                .slParcelUuid(parcelUuid)
                .reason(FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN)
                .detectedAt(OffsetDateTime.now().minusHours(2))
                .resolved(false)
                .build());
            flag2Id = f2.getId();

            FraudFlag f3 = fraudFlagRepo.save(FraudFlag.builder()
                .auction(auction)
                .slParcelUuid(parcelUuid)
                .reason(FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN)
                .detectedAt(OffsetDateTime.now().minusHours(1))
                .resolved(false)
                .build());
            flag3Id = f3.getId();

            // 1 open BOT_PRICE_DRIFT
            FraudFlag f4 = fraudFlagRepo.save(FraudFlag.builder()
                .auction(auction)
                .slParcelUuid(parcelUuid)
                .reason(FraudFlagReason.BOT_PRICE_DRIFT)
                .detectedAt(OffsetDateTime.now().minusMinutes(30))
                .resolved(false)
                .build());
            flag4Id = f4.getId();

            // 1 resolved PARCEL_DELETED_OR_MERGED
            FraudFlag f5 = fraudFlagRepo.save(FraudFlag.builder()
                .auction(auction)
                .slParcelUuid(parcelUuid)
                .reason(FraudFlagReason.PARCEL_DELETED_OR_MERGED)
                .detectedAt(OffsetDateTime.now().minusDays(1))
                .resolved(true)
                .resolvedAt(OffsetDateTime.now().minusHours(12))
                .resolvedBy(seller)
                .build());
            flag5Id = f5.getId();
        });
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                for (Long fid : new Long[]{flag1Id, flag2Id, flag3Id, flag4Id, flag5Id}) {
                    if (fid != null) st.execute("DELETE FROM fraud_flags WHERE id = " + fid);
                }
                if (auctionId != null) {
                    st.execute("DELETE FROM auction_parcel_snapshots WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM auctions WHERE id = " + auctionId);
                }
                if (sellerId != null) {
                    st.execute("DELETE FROM notification WHERE user_id = " + sellerId);
                    st.execute("DELETE FROM refresh_tokens WHERE user_id = " + sellerId);
                    st.execute("DELETE FROM users WHERE id = " + sellerId);
                }
            }
        }
        sellerId = null;
        auctionId = null;
        flag1Id = flag2Id = flag3Id = flag4Id = flag5Id = null;
    }

    @Test
    void list_openStatus_returnsOnlyOpenFlags() {
        PagedResponse<AdminFraudFlagSummaryDto> result = service.list("open", List.of(), PAGE);

        // The seeded open flags are 4 (3 OWNERSHIP + 1 BOT_PRICE_DRIFT)
        // Other tests may share DB — check seeded IDs are all present
        List<Long> ids = result.content().stream().map(AdminFraudFlagSummaryDto::id).toList();
        assertThat(ids).contains(flag1Id, flag2Id, flag3Id, flag4Id);
        assertThat(ids).doesNotContain(flag5Id);
        // All returned must be unresolved
        assertThat(result.content()).allMatch(f -> !f.resolved());
    }

    @Test
    void list_resolvedStatus_returnsOnlyResolvedFlags() {
        PagedResponse<AdminFraudFlagSummaryDto> result = service.list("resolved", List.of(), PAGE);

        List<Long> ids = result.content().stream().map(AdminFraudFlagSummaryDto::id).toList();
        assertThat(ids).contains(flag5Id);
        assertThat(ids).doesNotContain(flag1Id, flag2Id, flag3Id, flag4Id);
        assertThat(result.content()).allMatch(AdminFraudFlagSummaryDto::resolved);
    }

    @Test
    void list_allStatus_returnsAllFiveSeededFlags() {
        // Use a large page to avoid the oldest seeded flag (flag5, detectedAt -1d) being
        // pushed off page 0 when the DB has many existing fraud_flag rows.
        PageRequest bigPage = PageRequest.of(0, 1000, Sort.by(Sort.Direction.DESC, "detectedAt"));
        PagedResponse<AdminFraudFlagSummaryDto> result = service.list("all", List.of(), bigPage);

        List<Long> ids = result.content().stream().map(AdminFraudFlagSummaryDto::id).toList();
        assertThat(ids).contains(flag1Id, flag2Id, flag3Id, flag4Id, flag5Id);
    }

    @Test
    void list_reasonFilter_returnsOnlyMatchingFlags() {
        PagedResponse<AdminFraudFlagSummaryDto> result = service.list(
            "open", List.of(FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN), PAGE);

        List<Long> ids = result.content().stream().map(AdminFraudFlagSummaryDto::id).toList();
        assertThat(ids).contains(flag1Id, flag2Id, flag3Id);
        assertThat(ids).doesNotContain(flag4Id, flag5Id);
        assertThat(result.content())
            .allMatch(f -> f.reason() == FraudFlagReason.OWNERSHIP_CHANGED_TO_UNKNOWN);
    }

    @Test
    void list_sortedByDetectedAtDesc() {
        PagedResponse<AdminFraudFlagSummaryDto> result = service.list("open", List.of(), PAGE);

        List<Long> ids = result.content().stream().map(AdminFraudFlagSummaryDto::id).toList();
        // flag4 has the most recent detectedAt (minusMinutes(30)), flag3 is second
        int idx4 = ids.indexOf(flag4Id);
        int idx3 = ids.indexOf(flag3Id);
        assertThat(idx4).isLessThan(idx3);
    }
}
