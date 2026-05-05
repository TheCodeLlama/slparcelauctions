package com.slparcelauctions.backend.admin.reports;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.slparcelauctions.backend.admin.audit.AdminActionRepository;
import com.slparcelauctions.backend.admin.reports.dto.AdminReportDetailDto;
import com.slparcelauctions.backend.admin.reports.dto.AdminReportListingRowDto;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.user.Role;
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
class AdminReportServiceTest {

    @Autowired AdminReportService service;
    @Autowired ListingReportRepository reportRepo;
    @Autowired AuctionRepository auctionRepo;
    @Autowired UserRepository userRepo;
    @Autowired AdminActionRepository adminActionRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId;
    private Long adminId;
    private Long reporter1Id;
    private Long reporter2Id;
    private Long reporter3Id;
    private Long reporter4Id;
    private Long auctionId;
    private Long openReport1Id;
    private Long openReport2Id;
    private Long openReport3Id;
    private Long dismissedReportId;

    @BeforeEach
    void seed() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            User seller = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("rptadmin-seller-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Report Test Seller")
                .verified(true)
                .build());
            sellerId = seller.getId();

            User admin = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("rptadmin-admin-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Report Test Admin")
                .role(Role.ADMIN)
                .build());
            adminId = admin.getId();

            User reporter1 = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("rptadmin-r1-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Reporter One")
                .verified(true)
                .build());
            reporter1Id = reporter1.getId();

            User reporter2 = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("rptadmin-r2-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Reporter Two")
                .verified(true)
                .build());
            reporter2Id = reporter2.getId();

            User reporter3 = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("rptadmin-r3-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Reporter Three")
                .verified(true)
                .build());
            reporter3Id = reporter3.getId();

            User reporter4 = userRepo.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                .email("rptadmin-r4-" + UUID.randomUUID() + "@x.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Reporter Four")
                .verified(true)
                .build());
            reporter4Id = reporter4.getId();

            UUID parcelUuid = UUID.randomUUID();
            Auction auction = auctionRepo.save(Auction.builder()
                .seller(seller)
                .slParcelUuid(parcelUuid)
                .title("Admin Report Test Auction")
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
                .parcelName("Admin Report Test Parcel")
                .regionName("Test Region")
                .regionMaturityRating("GENERAL")
                .areaSqm(1024)
                .positionX(128.0).positionY(64.0).positionZ(22.0)
                .build());
            auctionRepo.save(auction);
            auctionId = auction.getId();

            ListingReport r1 = reportRepo.save(ListingReport.builder()
                .auction(auction)
                .reporter(reporter1)
                .subject("Open Report 1")
                .reason(ListingReportReason.SHILL_BIDDING)
                .details("Details 1")
                .status(ListingReportStatus.OPEN)
                .build());
            openReport1Id = r1.getId();

            ListingReport r2 = reportRepo.save(ListingReport.builder()
                .auction(auction)
                .reporter(reporter2)
                .subject("Open Report 2")
                .reason(ListingReportReason.FRAUDULENT_SELLER)
                .details("Details 2")
                .status(ListingReportStatus.OPEN)
                .build());
            openReport2Id = r2.getId();

            ListingReport r3 = reportRepo.save(ListingReport.builder()
                .auction(auction)
                .reporter(reporter3)
                .subject("Open Report 3")
                .reason(ListingReportReason.INACCURATE_DESCRIPTION)
                .details("Details 3")
                .status(ListingReportStatus.OPEN)
                .build());
            openReport3Id = r3.getId();

            ListingReport dismissed = reportRepo.save(ListingReport.builder()
                .auction(auction)
                .reporter(reporter4)
                .subject("Dismissed Report")
                .reason(ListingReportReason.OTHER)
                .details("Already dismissed")
                .status(ListingReportStatus.DISMISSED)
                .build());
            dismissedReportId = dismissed.getId();
        });
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                if (auctionId != null) {
                    st.execute("DELETE FROM listing_reports WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM cancellation_logs WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM bot_tasks WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM admin_actions WHERE target_type = 'LISTING' AND target_id = " + auctionId);
                    st.execute("DELETE FROM auction_parcel_snapshots WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM auctions WHERE id = " + auctionId);
                }
                if (adminId != null) {
                    st.execute("DELETE FROM admin_actions WHERE admin_user_id = " + adminId);
                    st.execute("DELETE FROM notification WHERE user_id = " + adminId);
                    st.execute("DELETE FROM refresh_tokens WHERE user_id = " + adminId);
                    st.execute("DELETE FROM users WHERE id = " + adminId);
                }
                for (Long id : List.of(sellerId, reporter1Id, reporter2Id, reporter3Id, reporter4Id)) {
                    if (id != null) {
                        st.execute("DELETE FROM notification WHERE user_id = " + id);
                        st.execute("DELETE FROM sl_im_message WHERE user_id = " + id);
                        st.execute("DELETE FROM refresh_tokens WHERE user_id = " + id);
                        st.execute("DELETE FROM users WHERE id = " + id);
                    }
                }
            }
        }
        sellerId = adminId = reporter1Id = reporter2Id = reporter3Id = reporter4Id = null;
        auctionId = null;
        openReport1Id = openReport2Id = openReport3Id = dismissedReportId = null;
    }

    @Test
    void listGrouped_openOnly_returnsOneRowPerAuction_sortedByReportCount() {
        PagedResponse<AdminReportListingRowDto> resp =
            service.listGrouped(ListingReportStatus.OPEN, PageRequest.of(0, 25));

        List<AdminReportListingRowDto> rows = resp.content().stream()
            .filter(r -> r.auctionId().equals(auctionId))
            .toList();

        assertThat(rows).hasSize(1);
        AdminReportListingRowDto row = rows.get(0);
        assertThat(row.auctionId()).isEqualTo(auctionId);
        assertThat(row.openReportCount()).isEqualTo(3L);
        assertThat(row.auctionTitle()).isEqualTo("Admin Report Test Auction");
        assertThat(row.auctionStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(row.sellerUserId()).isEqualTo(sellerId);
    }

    @Test
    void findByListing_returnsAllReports_orderedNewestFirst() {
        List<AdminReportDetailDto> reports = service.findByListing(auctionId);

        assertThat(reports).hasSize(4);
        assertThat(reports.get(0).createdAt()).isAfterOrEqualTo(reports.get(1).createdAt());
    }

    @Test
    void dismiss_marksDismissed_incrementsReporterFrivolousCounter() {
        service.dismiss(openReport1Id, adminId, "Frivolous report");

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            ListingReport report = reportRepo.findById(openReport1Id).orElseThrow();
            assertThat(report.getStatus()).isEqualTo(ListingReportStatus.DISMISSED);
            assertThat(report.getAdminNotes()).isEqualTo("Frivolous report");
            assertThat(report.getReviewedAt()).isNotNull();
            assertThat(report.getReviewedBy()).isNotNull();

            User reporter = userRepo.findById(reporter1Id).orElseThrow();
            assertThat(reporter.getDismissedReportsCount()).isEqualTo(1L);
        });

        long actionCount = adminActionRepo.findAll().stream()
            .filter(a -> a.getTargetId().equals(openReport1Id)
                && a.getTargetType().name().equals("REPORT"))
            .count();
        assertThat(actionCount).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void warnSeller_marksOnlyOpenAsReviewed_dismissedPreserved() {
        service.warnSeller(auctionId, adminId, "Final warning");

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            ListingReport r1 = reportRepo.findById(openReport1Id).orElseThrow();
            ListingReport r2 = reportRepo.findById(openReport2Id).orElseThrow();
            ListingReport r3 = reportRepo.findById(openReport3Id).orElseThrow();
            ListingReport dismissed = reportRepo.findById(dismissedReportId).orElseThrow();

            assertThat(r1.getStatus()).isEqualTo(ListingReportStatus.REVIEWED);
            assertThat(r2.getStatus()).isEqualTo(ListingReportStatus.REVIEWED);
            assertThat(r3.getStatus()).isEqualTo(ListingReportStatus.REVIEWED);
            assertThat(dismissed.getStatus()).isEqualTo(ListingReportStatus.DISMISSED);
        });
    }

    @Test
    void suspend_callsSuspendByAdmin_marksOpenAsActionTaken_dismissedPreserved() {
        service.suspend(auctionId, adminId, "Policy violation");

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Auction auction = auctionRepo.findById(auctionId).orElseThrow();
            assertThat(auction.getStatus()).isEqualTo(AuctionStatus.SUSPENDED);

            ListingReport r1 = reportRepo.findById(openReport1Id).orElseThrow();
            ListingReport r2 = reportRepo.findById(openReport2Id).orElseThrow();
            ListingReport r3 = reportRepo.findById(openReport3Id).orElseThrow();
            ListingReport dismissed = reportRepo.findById(dismissedReportId).orElseThrow();

            assertThat(r1.getStatus()).isEqualTo(ListingReportStatus.ACTION_TAKEN);
            assertThat(r2.getStatus()).isEqualTo(ListingReportStatus.ACTION_TAKEN);
            assertThat(r3.getStatus()).isEqualTo(ListingReportStatus.ACTION_TAKEN);
            assertThat(dismissed.getStatus()).isEqualTo(ListingReportStatus.DISMISSED);
        });
    }

    @Test
    void cancel_callsCancelByAdmin_marksOpenAsActionTaken_dismissedPreserved() {
        service.cancel(auctionId, adminId, "Removed for violations");

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Auction auction = auctionRepo.findById(auctionId).orElseThrow();
            assertThat(auction.getStatus()).isEqualTo(AuctionStatus.CANCELLED);

            ListingReport r1 = reportRepo.findById(openReport1Id).orElseThrow();
            ListingReport r2 = reportRepo.findById(openReport2Id).orElseThrow();
            ListingReport r3 = reportRepo.findById(openReport3Id).orElseThrow();
            ListingReport dismissed = reportRepo.findById(dismissedReportId).orElseThrow();

            assertThat(r1.getStatus()).isEqualTo(ListingReportStatus.ACTION_TAKEN);
            assertThat(r2.getStatus()).isEqualTo(ListingReportStatus.ACTION_TAKEN);
            assertThat(r3.getStatus()).isEqualTo(ListingReportStatus.ACTION_TAKEN);
            assertThat(dismissed.getStatus()).isEqualTo(ListingReportStatus.DISMISSED);
        });
    }
}
