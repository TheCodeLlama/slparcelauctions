package com.slparcelauctions.backend.auction;

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

import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationRepository;
import com.slparcelauctions.backend.notification.NotificationWsBroadcasterPort;
import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.parcel.ParcelRepository;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.testsupport.TestRegions;

/**
 * Integration tests for {@link CancellationService#cancelByAdmin}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Auction flips to CANCELLED with a log row that records cancelledByAdminId.</li>
 *   <li>seller.cancelledWithBids is NOT incremented (no penalty ladder).</li>
 *   <li>{@code countPriorOffensesWithBids} excludes admin-cancel rows.</li>
 *   <li>Seller receives a LISTING_REMOVED_BY_ADMIN notification.</li>
 * </ul>
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
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
class CancellationServiceCancelByAdminTest {

    @Autowired CancellationService cancellationService;
    @Autowired AuctionRepository auctionRepo;
    @Autowired CancellationLogRepository cancellationLogRepo;
    @Autowired ParcelRepository parcelRepo;
    @Autowired UserRepository userRepo;
    @Autowired NotificationRepository notifRepo;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;

    @MockitoBean NotificationWsBroadcasterPort wsBroadcaster;

    private Long sellerId, adminId, auctionId, parcelId;

    @AfterEach
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                if (auctionId != null) {
                    st.execute("DELETE FROM cancellation_logs WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM bids WHERE auction_id = " + auctionId);
                    st.execute("DELETE FROM auctions WHERE id = " + auctionId);
                }
                if (parcelId != null) {
                    st.execute("DELETE FROM parcels WHERE id = " + parcelId);
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var st = conn.createStatement()) {
                for (Long id : new Long[]{sellerId, adminId}) {
                    if (id != null) {
                        st.execute("DELETE FROM notification WHERE user_id = " + id);
                        st.execute("DELETE FROM cancellation_logs WHERE seller_id = " + id);
                        st.execute("DELETE FROM refresh_tokens WHERE user_id = " + id);
                        st.execute("DELETE FROM users WHERE id = " + id);
                    }
                }
            }
        }
        sellerId = adminId = auctionId = parcelId = null;
    }

    private User newUser(String prefix, Role role) {
        return new TransactionTemplate(txManager).execute(s -> userRepo.save(User.builder()
                .email(prefix + "-" + UUID.randomUUID() + "@test.com")
                .passwordHash("h")
                .displayName(prefix)
                .slAvatarUuid(UUID.randomUUID())
                .verified(true)
                .role(role)
                .cancelledWithBids(0)
                .penaltyBalanceOwed(0L)
                .build()));
    }

    private Auction buildActiveAuction(User seller, int bidCount) {
        return new TransactionTemplate(txManager).execute(s -> {
            Parcel p = parcelRepo.save(Parcel.builder()
                    .region(TestRegions.mainland())
                    .slParcelUuid(UUID.randomUUID())
                    .ownerUuid(seller.getSlAvatarUuid())
                    .ownerType("agent")
                                                            .areaSqm(512)
                                        .verified(true)
                    .verifiedAt(OffsetDateTime.now())
                    .build());
            parcelId = p.getId();
            Auction a = auctionRepo.save(Auction.builder()
                    .title("Admin Cancel Test Lot")
                    .parcel(p)
                    .seller(seller)
                    .status(AuctionStatus.ACTIVE)
                    .verificationMethod(VerificationMethod.UUID_ENTRY)
                    .verificationTier(VerificationTier.SCRIPT)
                    .startingBid(1000L)
                    .currentBid(bidCount > 0 ? 1500L : 0L)
                    .bidCount(bidCount)
                    .durationHours(168)
                    .snipeProtect(false)
                    .listingFeePaid(true)
                    .consecutiveWorldApiFailures(0)
                    .commissionRate(new BigDecimal("0.05"))
                    .agentFeeRate(BigDecimal.ZERO)
                    .startsAt(OffsetDateTime.now().minusHours(1))
                    .endsAt(OffsetDateTime.now().plusHours(24))
                    .originalEndsAt(OffsetDateTime.now().plusHours(24))
                    .build());
            auctionId = a.getId();
            return a;
        });
    }

    @Test
    void cancelByAdmin_flipsCancelled_writesLogWithCancelledByAdminId() {
        User seller = newUser("admin-cancel-seller", Role.USER); sellerId = seller.getId();
        User admin  = newUser("admin-cancel-admin",  Role.ADMIN); adminId  = admin.getId();
        buildActiveAuction(seller, 0);

        cancellationService.cancelByAdmin(auctionId, adminId, "TOS violation");

        // Auction must be CANCELLED
        Auction saved = new TransactionTemplate(txManager).execute(
                s -> auctionRepo.findById(auctionId).orElseThrow());
        assertThat(saved.getStatus()).isEqualTo(AuctionStatus.CANCELLED);

        // Log row must exist with cancelledByAdminId = adminId and penaltyKind = NONE
        var logs = cancellationLogRepo.findLatestByAuctionId(
                auctionId, org.springframework.data.domain.PageRequest.of(0, 1));
        assertThat(logs).hasSize(1);
        CancellationLog log = logs.get(0);
        assertThat(log.getCancelledByAdminId()).isEqualTo(adminId);
        assertThat(log.getPenaltyKind()).isEqualTo(CancellationOffenseKind.NONE);
        assertThat(log.getPenaltyAmountL()).isNull();
    }

    @Test
    void cancelByAdmin_doesNotIncrementSellerCancelledWithBidsCounter() {
        User seller = newUser("admin-cancel-nobump-seller", Role.USER); sellerId = seller.getId();
        User admin  = newUser("admin-cancel-nobump-admin",  Role.ADMIN); adminId  = admin.getId();
        buildActiveAuction(seller, 0);

        cancellationService.cancelByAdmin(auctionId, adminId, "No penalty");

        User reloaded = new TransactionTemplate(txManager).execute(
                s -> userRepo.findById(sellerId).orElseThrow());
        assertThat(reloaded.getCancelledWithBids()).isZero();
    }

    @Test
    void cancelByAdmin_priorOffensesQueryExcludesAdminCancel() {
        User seller = newUser("admin-cancel-ladder-seller", Role.USER); sellerId = seller.getId();
        User admin  = newUser("admin-cancel-ladder-admin",  Role.ADMIN); adminId  = admin.getId();
        // Auction with bidCount=0 so hadBids=false — still creates the log row.
        // Use bidCount=1 to ensure hadBids=true on the admin-cancel log row,
        // which is the condition that countPriorOffensesWithBids filters on.
        buildActiveAuction(seller, 1);

        cancellationService.cancelByAdmin(auctionId, adminId, "Staff removal");

        // Admin-cancel rows have cancelledByAdminId IS NOT NULL, so they must be
        // excluded from countPriorOffensesWithBids. Result must be 0.
        long count = new TransactionTemplate(txManager).execute(
                s -> cancellationLogRepo.countPriorOffensesWithBids(sellerId));
        assertThat(count).isZero();
    }

    @Test
    void cancelByAdmin_publishesListingRemovedByAdminToSeller() {
        User seller = newUser("admin-cancel-notif-seller", Role.USER); sellerId = seller.getId();
        User admin  = newUser("admin-cancel-notif-admin",  Role.ADMIN); adminId  = admin.getId();
        buildActiveAuction(seller, 0);

        cancellationService.cancelByAdmin(auctionId, adminId, "Policy breach");

        // Seller must receive LISTING_REMOVED_BY_ADMIN
        var sellerNotifs = notifRepo.findAllByUserId(sellerId);
        assertThat(sellerNotifs)
                .filteredOn(n -> n.getCategory() == NotificationCategory.LISTING_REMOVED_BY_ADMIN)
                .hasSize(1);
    }
}
