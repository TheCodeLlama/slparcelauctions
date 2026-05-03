package com.slparcelauctions.backend.admin.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
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

import com.slparcelauctions.backend.admin.reports.dto.MyReportResponse;
import com.slparcelauctions.backend.admin.reports.dto.ReportRequest;
import com.slparcelauctions.backend.admin.reports.exception.AuctionNotReportableException;
import com.slparcelauctions.backend.admin.reports.exception.CannotReportOwnListingException;
import com.slparcelauctions.backend.admin.reports.exception.MustBeVerifiedToReportException;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
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
class UserReportServiceTest {

    @Autowired UserReportService service;
    @Autowired ListingReportRepository reportRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired UserRepository userRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId;
    private Long reporterId;
    private Long auctionId;

    @BeforeEach
    void seed() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User seller = userRepo.save(User.builder()
                .email("rpt-seller-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .build());
            sellerId = seller.getId();

            User reporter = userRepo.save(User.builder()
                .email("rpt-reporter-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .build());
            reporterId = reporter.getId();

            UUID parcelUuid = UUID.randomUUID();
            Auction auction = auctionRepo.save(Auction.builder()
                .seller(seller)
                .slParcelUuid(parcelUuid)
                .title("Reportable Auction")
                .status(AuctionStatus.ACTIVE)
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
                .parcelName("Reportable Parcel")
                .regionName("Test Region")
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
            auctionRepo.save(auction);
            auctionId = auction.getId();
        });
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                if (auctionId != null) {
                    st.execute("DELETE FROM listing_reports WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM auction_parcel_snapshots WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM auctions WHERE id = " + auctionId);
                }
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
        sellerId = reporterId = auctionId = null;
    }

    // -------------------------------------------------------------------------
    // upsertReport — happy path
    // -------------------------------------------------------------------------

    @Test
    void upsertReport_firstSubmit_createsReportWithStatusOpen() {
        ReportRequest req = new ReportRequest("Bad listing", ListingReportReason.INACCURATE_DESCRIPTION, "The details are wrong.");

        MyReportResponse resp = service.upsertReport(auctionId, reporterId, req);

        assertThat(resp.id()).isNotNull();
        assertThat(resp.subject()).isEqualTo("Bad listing");
        assertThat(resp.reason()).isEqualTo(ListingReportReason.INACCURATE_DESCRIPTION);
        assertThat(resp.details()).isEqualTo("The details are wrong.");
        assertThat(resp.status()).isEqualTo(ListingReportStatus.OPEN);
        assertThat(resp.createdAt()).isNotNull();
        assertThat(resp.updatedAt()).isNotNull();
    }

    @Test
    void upsertReport_resubmit_replacesExistingRow_resetsToOpen() {
        ReportRequest req1 = new ReportRequest("First subject", ListingReportReason.WRONG_TAGS, "First details.");
        MyReportResponse first = service.upsertReport(auctionId, reporterId, req1);

        // Manually set status to DISMISSED
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            ListingReport r = reportRepo.findById(first.id()).orElseThrow();
            r.setStatus(ListingReportStatus.DISMISSED);
            reportRepo.save(r);
        });

        // Resubmit
        ReportRequest req2 = new ReportRequest("Updated subject", ListingReportReason.SHILL_BIDDING, "New details.");
        MyReportResponse second = service.upsertReport(auctionId, reporterId, req2);

        assertThat(second.id()).isEqualTo(first.id()); // same row
        assertThat(second.subject()).isEqualTo("Updated subject");
        assertThat(second.reason()).isEqualTo(ListingReportReason.SHILL_BIDDING);
        assertThat(second.details()).isEqualTo("New details.");
        assertThat(second.status()).isEqualTo(ListingReportStatus.OPEN);
    }

    // -------------------------------------------------------------------------
    // upsertReport — gate failures
    // -------------------------------------------------------------------------

    @Test
    void upsertReport_unverifiedReporter_throwsMustBeVerified() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User r = userRepo.findById(reporterId).orElseThrow();
            r.setVerified(false);
            userRepo.save(r);
        });

        ReportRequest req = new ReportRequest("x", ListingReportReason.OTHER, "y");
        assertThatThrownBy(() -> service.upsertReport(auctionId, reporterId, req))
            .isInstanceOf(MustBeVerifiedToReportException.class);
    }

    @Test
    void upsertReport_ownListing_throwsCannotReportOwn() {
        ReportRequest req = new ReportRequest("x", ListingReportReason.OTHER, "y");
        assertThatThrownBy(() -> service.upsertReport(auctionId, sellerId, req))
            .isInstanceOf(CannotReportOwnListingException.class);
    }

    @Test
    void upsertReport_nonActiveAuction_throwsAuctionNotReportable() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Auction a = auctionRepo.findById(auctionId).orElseThrow();
            a.setStatus(AuctionStatus.CANCELLED);
            auctionRepo.save(a);
        });

        ReportRequest req = new ReportRequest("x", ListingReportReason.OTHER, "y");
        assertThatThrownBy(() -> service.upsertReport(auctionId, reporterId, req))
            .isInstanceOf(AuctionNotReportableException.class)
            .satisfies(ex -> assertThat(((AuctionNotReportableException) ex).getCurrentStatus())
                .isEqualTo(AuctionStatus.CANCELLED));
    }

    // -------------------------------------------------------------------------
    // findMyReport
    // -------------------------------------------------------------------------

    @Test
    void findMyReport_noRow_returnsEmpty() {
        Optional<MyReportResponse> result = service.findMyReport(auctionId, reporterId);
        assertThat(result).isEmpty();
    }

    @Test
    void findMyReport_withRow_returnsPopulated() {
        ReportRequest req = new ReportRequest("My subject", ListingReportReason.DUPLICATE_LISTING, "Details here.");
        service.upsertReport(auctionId, reporterId, req);

        Optional<MyReportResponse> result = service.findMyReport(auctionId, reporterId);

        assertThat(result).isPresent();
        assertThat(result.get().subject()).isEqualTo("My subject");
        assertThat(result.get().reason()).isEqualTo(ListingReportReason.DUPLICATE_LISTING);
        assertThat(result.get().status()).isEqualTo(ListingReportStatus.OPEN);
    }
}
