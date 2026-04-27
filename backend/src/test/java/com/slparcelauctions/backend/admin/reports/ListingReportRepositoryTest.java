package com.slparcelauctions.backend.admin.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
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
class ListingReportRepositoryTest {

    @Autowired ListingReportRepository reportRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId;
    private Long reporterId;
    private Long parcelId;
    private Long auctionId;

    @BeforeEach
    void seed() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User seller = userRepo.save(User.builder()
                .email("lrr-seller-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .build());
            sellerId = seller.getId();

            User reporter = userRepo.save(User.builder()
                .email("lrr-reporter-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .build());
            reporterId = reporter.getId();

            Parcel parcel = parcelRepo.save(Parcel.builder()
                .slParcelUuid(UUID.randomUUID())
                .regionName("LrrRegion")
                .ownerUuid(seller.getSlAvatarUuid())
                .areaSqm(512)
                .build());
            parcelId = parcel.getId();

            Auction auction = auctionRepo.save(Auction.builder()
                .seller(seller)
                .parcel(parcel)
                .title("LRR Test Auction")
                .status(AuctionStatus.ACTIVE)
                .verificationTier(VerificationTier.SCRIPT)
                .verificationMethod(VerificationMethod.UUID_ENTRY)
                .startingBid(100L)
                .durationHours(24)
                .endsAt(OffsetDateTime.now().plusHours(24))
                .build());
            auctionId = auction.getId();
        });
    }

    @AfterEach
    void cleanup() throws Exception {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            if (auctionId != null) {
                reportRepo.deleteAll(reportRepo.findByAuctionIdOrderByCreatedAtDesc(auctionId));
                auctionRepo.findById(auctionId).ifPresent(auctionRepo::delete);
            }
            if (parcelId != null) {
                parcelRepo.findById(parcelId).ifPresent(parcelRepo::delete);
            }
        });
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                if (reporterId != null) {
                    st.execute("DELETE FROM notification WHERE user_id = " + reporterId);
                    st.execute("DELETE FROM refresh_tokens WHERE user_id = " + reporterId);
                    st.execute("DELETE FROM users WHERE id = " + reporterId);
                }
                if (sellerId != null) {
                    st.execute("DELETE FROM notification WHERE user_id = " + sellerId);
                    st.execute("DELETE FROM refresh_tokens WHERE user_id = " + sellerId);
                    st.execute("DELETE FROM users WHERE id = " + sellerId);
                }
            }
        }
        sellerId = reporterId = parcelId = auctionId = null;
    }

    @Test
    void findByAuctionIdAndReporterId_noRow_returnsEmpty() {
        Optional<ListingReport> result = reportRepo.findByAuctionIdAndReporterId(auctionId, reporterId);
        assertThat(result).isEmpty();
    }

    @Test
    void findByAuctionIdAndReporterId_withRow_returnsReport() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Auction auction = auctionRepo.findById(auctionId).orElseThrow();
            User reporter = userRepo.findById(reporterId).orElseThrow();
            reportRepo.save(ListingReport.builder()
                .auction(auction)
                .reporter(reporter)
                .subject("Test subject")
                .reason(ListingReportReason.WRONG_TAGS)
                .details("Some details")
                .build());
        });

        Optional<ListingReport> result = reportRepo.findByAuctionIdAndReporterId(auctionId, reporterId);
        assertThat(result).isPresent();
        assertThat(result.get().getSubject()).isEqualTo("Test subject");
        assertThat(result.get().getReason()).isEqualTo(ListingReportReason.WRONG_TAGS);
        assertThat(result.get().getStatus()).isEqualTo(ListingReportStatus.OPEN);
    }

    @Test
    void findByAuctionIdOrderByCreatedAtDesc_returnsOnlyForThatAuction() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Auction auction = auctionRepo.findById(auctionId).orElseThrow();
            User reporter = userRepo.findById(reporterId).orElseThrow();
            reportRepo.save(ListingReport.builder()
                .auction(auction)
                .reporter(reporter)
                .subject("Subject A")
                .reason(ListingReportReason.SHILL_BIDDING)
                .details("Details A")
                .build());
        });

        List<ListingReport> reports = reportRepo.findByAuctionIdOrderByCreatedAtDesc(auctionId);
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).getSubject()).isEqualTo("Subject A");
    }

    @Test
    void countByStatus_countsOpenReports() {
        long openBefore = reportRepo.countByStatus(ListingReportStatus.OPEN);

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Auction auction = auctionRepo.findById(auctionId).orElseThrow();
            User reporter = userRepo.findById(reporterId).orElseThrow();
            reportRepo.save(ListingReport.builder()
                .auction(auction)
                .reporter(reporter)
                .subject("Count test")
                .reason(ListingReportReason.OTHER)
                .details("Details")
                .build());
        });

        long openAfter = reportRepo.countByStatus(ListingReportStatus.OPEN);
        assertThat(openAfter).isEqualTo(openBefore + 1);
    }
}
