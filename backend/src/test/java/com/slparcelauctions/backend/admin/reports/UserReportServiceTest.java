package com.slparcelauctions.backend.admin.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false"
})
class UserReportServiceTest {

    @Autowired UserReportService service;
    @Autowired ListingReportRepository reportRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired UserRepository userRepo;
    @Autowired com.slparcelauctions.backend.realty.RealtyGroupRepository realtyGroupRepo;
    @Autowired com.slparcelauctions.backend.realty.reports.RealtyGroupReportRepository realtyGroupReportRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId;
    private Long reporterId;
    private Long auctionId;

    @BeforeEach
    void seed() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User seller = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("rpt-seller-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .build());
            sellerId = seller.getId();

            User reporter = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
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

        assertThat(resp.publicId()).isNotNull();
        assertThat(resp.entityType()).isEqualTo("LISTING");
        assertThat(resp.entityPublicId()).isNotNull();
        assertThat(resp.subject()).isEqualTo("Bad listing");
        assertThat(resp.reason()).isEqualTo(ListingReportReason.INACCURATE_DESCRIPTION.name());
        assertThat(resp.details()).isEqualTo("The details are wrong.");
        assertThat(resp.status()).isEqualTo(ListingReportStatus.OPEN.name());
        assertThat(resp.createdAt()).isNotNull();
        assertThat(resp.updatedAt()).isNotNull();
    }

    @Test
    void upsertReport_resubmit_replacesExistingRow_resetsToOpen() {
        ReportRequest req1 = new ReportRequest("First subject", ListingReportReason.WRONG_TAGS, "First details.");
        MyReportResponse first = service.upsertReport(auctionId, reporterId, req1);

        // Manually set status to DISMISSED
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            ListingReport r = reportRepo.findByPublicId(first.publicId()).orElseThrow();
            r.setStatus(ListingReportStatus.DISMISSED);
            reportRepo.save(r);
        });

        // Resubmit
        ReportRequest req2 = new ReportRequest("Updated subject", ListingReportReason.SHILL_BIDDING, "New details.");
        MyReportResponse second = service.upsertReport(auctionId, reporterId, req2);

        assertThat(second.publicId()).isEqualTo(first.publicId()); // same row
        assertThat(second.subject()).isEqualTo("Updated subject");
        assertThat(second.reason()).isEqualTo(ListingReportReason.SHILL_BIDDING.name());
        assertThat(second.details()).isEqualTo("New details.");
        assertThat(second.status()).isEqualTo(ListingReportStatus.OPEN.name());
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
        assertThat(result.get().reason()).isEqualTo(ListingReportReason.DUPLICATE_LISTING.name());
        assertThat(result.get().status()).isEqualTo(ListingReportStatus.OPEN.name());
    }

    // -------------------------------------------------------------------------
    // findMyReports — merged listing + group reports
    // -------------------------------------------------------------------------

    @Test
    void findMyReports_returnsBothListingAndGroupReports_sortedByCreatedAtDesc() {
        // Seed 1 listing report at t=0
        ReportRequest listingReq = new ReportRequest(
            "Listing subject", ListingReportReason.INACCURATE_DESCRIPTION, "Listing details.");
        com.slparcelauctions.backend.admin.reports.dto.MyReportResponse listingResp =
            service.upsertReport(auctionId, reporterId, listingReq);
        UUID listingReportPublicId = listingResp.publicId();

        // Seed 1 group report via a direct repo write — bypasses the rate limiter
        // and group-membership gate so the test stays focused on the merge logic.
        final Long[] groupIdRef = new Long[1];
        final UUID[] groupPublicIdRef = new UUID[1];
        final UUID[] groupReportPublicIdRef = new UUID[1];
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User reporter = userRepo.findById(reporterId).orElseThrow();
            User leader = userRepo.save(User.builder()
                .username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("rpt-leader-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .build());
            com.slparcelauctions.backend.realty.RealtyGroup group =
                com.slparcelauctions.backend.realty.RealtyGroup.builder()
                    .name("Test Group " + UUID.randomUUID().toString().substring(0, 8))
                    .slug("group-" + UUID.randomUUID().toString().substring(0, 8))
                    .leaderId(leader.getId())
                    .build();
            group = realtyGroupRepo.save(group);
            groupIdRef[0] = group.getId();
            groupPublicIdRef[0] = group.getPublicId();

            com.slparcelauctions.backend.realty.reports.RealtyGroupReport rg =
                com.slparcelauctions.backend.realty.reports.RealtyGroupReport.builder()
                    .realtyGroup(group)
                    .reporter(reporter)
                    .reason(com.slparcelauctions.backend.realty.reports.RealtyGroupReportReason.FRAUDULENT_LISTINGS)
                    .details("Group report details — clearly bogus listings across the roster.")
                    .status(com.slparcelauctions.backend.realty.reports.RealtyGroupReportStatus.OPEN)
                    .build();
            rg = realtyGroupReportRepo.save(rg);
            groupReportPublicIdRef[0] = rg.getPublicId();
        });

        // Force the group report's createdAt to be 1s after the listing report.
        // Hibernate sets @CreationTimestamp on persist; we override via native
        // UPDATE so the ORDER BY assertion has a deterministic ordering.
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                OffsetDateTime base = OffsetDateTime.now().minusSeconds(10);
                st.execute("UPDATE listing_reports SET created_at = '"
                    + base.toString() + "' WHERE public_id = '"
                    + listingReportPublicId + "'");
                st.execute("UPDATE realty_group_reports SET created_at = '"
                    + base.plusSeconds(1).toString() + "' WHERE public_id = '"
                    + groupReportPublicIdRef[0] + "'");
            }
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }

        List<com.slparcelauctions.backend.admin.reports.dto.MyReportResponse> merged =
            service.findMyReports(reporterId);

        assertThat(merged).hasSize(2);
        // Newest first — the group report (t+1) precedes the listing report (t).
        assertThat(merged.get(0).entityType()).isEqualTo("REALTY_GROUP");
        assertThat(merged.get(0).publicId()).isEqualTo(groupReportPublicIdRef[0]);
        assertThat(merged.get(0).entityPublicId()).isEqualTo(groupPublicIdRef[0]);
        assertThat(merged.get(0).reason()).isEqualTo("FRAUDULENT_LISTINGS");
        assertThat(merged.get(0).status()).isEqualTo("OPEN");

        assertThat(merged.get(1).entityType()).isEqualTo("LISTING");
        assertThat(merged.get(1).publicId()).isEqualTo(listingReportPublicId);
        assertThat(merged.get(1).reason()).isEqualTo(ListingReportReason.INACCURATE_DESCRIPTION.name());
        assertThat(merged.get(1).subject()).isEqualTo("Listing subject");

        // Cleanup: remove the group + group report we created here (the @AfterEach
        // hook only knows about the listing-side seeds).
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                st.execute("DELETE FROM realty_group_reports WHERE realty_group_id = " + groupIdRef[0]);
                st.execute("DELETE FROM realty_groups WHERE id = " + groupIdRef[0]);
            }
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
