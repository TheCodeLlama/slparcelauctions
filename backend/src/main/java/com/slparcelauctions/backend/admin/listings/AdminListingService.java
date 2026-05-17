package com.slparcelauctions.backend.admin.listings;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.AdminAuctionService;
import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.admin.exception.AuctionNotSuspendedException;
import com.slparcelauctions.backend.admin.listings.dto.AdminListingFilterParams;
import com.slparcelauctions.backend.admin.listings.dto.AdminListingRowDto;
import com.slparcelauctions.backend.admin.listings.dto.SetFeaturedRequest;
import com.slparcelauctions.backend.admin.listings.exception.AdminListingStateException;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.CancellationService;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.featured.FeaturedCache;
import com.slparcelauctions.backend.auction.featured.FeaturedCategory;
import com.slparcelauctions.backend.auction.monitoring.SuspensionService;
import com.slparcelauctions.backend.notification.NotificationPublisher;

import lombok.RequiredArgsConstructor;

/**
 * Backs the admin listings table — both the read-side list query and the four
 * standalone moderation actions (warn, suspend, cancel, reinstate). Each
 * mutation:
 *
 * <ol>
 *   <li>Resolves the auction by {@code publicId} → throws {@code LISTING_NOT_FOUND}
 *       on miss.</li>
 *   <li>Delegates the state flip + downstream side effects (bot lifecycle,
 *       bidder notifications) to the existing shared services
 *       {@link SuspensionService}, {@link CancellationService},
 *       {@link AdminAuctionService}.</li>
 *   <li>For warn (no shared service exists), publishes the seller-side
 *       notification directly.</li>
 *   <li>Records an {@link AdminActionType} audit row with
 *       {@code metadata.source = "ADMIN_LISTINGS_TABLE"} so the audit log can
 *       distinguish report-triggered actions from table-triggered ones.</li>
 *   <li>Translates state-precondition exceptions into
 *       {@link AdminListingStateException} so the controller emits a uniform
 *       problem-detail shape.</li>
 * </ol>
 *
 * <p>Note: {@link AdminAuctionService#reinstate} already publishes the
 * seller-side reinstate notification — this service does NOT double-publish.
 */
@Service
@RequiredArgsConstructor
public class AdminListingService {

    private final AuctionRepository auctionRepo;
    private final AdminListingQueryRepository queryRepo;
    private final AdminActionService adminActionService;
    private final NotificationPublisher notificationPublisher;
    private final SuspensionService suspensionService;
    private final CancellationService cancellationService;
    private final AdminAuctionService adminAuctionService;
    private final FeaturedCache featuredCache;

    private static final Map<String, Object> SOURCE_METADATA =
        Map.of("source", "ADMIN_LISTINGS_TABLE");

    @Transactional(readOnly = true)
    public Page<AdminListingRowDto> list(AdminListingFilterParams params, Pageable pageable) {
        return queryRepo.search(params, pageable);
    }

    @Transactional
    public void warn(UUID publicId, Long adminUserId, String notes) {
        Auction auction = resolveOrThrow(publicId);

        notificationPublisher.listingWarned(
            auction.getSeller().getId(),
            auction.getId(),
            auction.getParcelSnapshot() != null
                ? auction.getParcelSnapshot().getRegionName()
                : null,
            notes
        );

        adminActionService.record(
            adminUserId,
            AdminActionType.WARN_SELLER_FROM_REPORT,
            AdminActionTargetType.LISTING,
            auction.getId(),
            notes,
            SOURCE_METADATA
        );
    }

    @Transactional
    public void suspend(UUID publicId, Long adminUserId, String notes) {
        Auction auction = resolveOrThrow(publicId);

        try {
            suspensionService.suspendByAdmin(auction, adminUserId, notes);
        } catch (InvalidAuctionStateException e) {
            throw new AdminListingStateException(
                "INVALID_STATUS_FOR_ACTION",
                "Cannot suspend listing in status " + auction.getStatus());
        } catch (IllegalStateException e) {
            throw new AdminListingStateException("INVALID_STATUS_FOR_ACTION", e.getMessage());
        }

        adminActionService.record(
            adminUserId,
            AdminActionType.SUSPEND_LISTING_FROM_REPORT,
            AdminActionTargetType.LISTING,
            auction.getId(),
            notes,
            SOURCE_METADATA
        );
    }

    @Transactional
    public void cancel(UUID publicId, Long adminUserId, String notes) {
        Auction auction = resolveOrThrow(publicId);
        Long auctionId = auction.getId();

        try {
            if (auction.getStatus() == AuctionStatus.TRANSFER_PENDING) {
                cancellationService.cancelByAdminFromEscrow(auctionId, adminUserId, notes);
            } else {
                cancellationService.cancelByAdmin(auctionId, adminUserId, notes);
            }
        } catch (AuctionNotFoundException e) {
            throw new AdminListingStateException("LISTING_NOT_FOUND", e.getMessage());
        } catch (InvalidAuctionStateException e) {
            throw new AdminListingStateException(
                "INVALID_STATUS_FOR_ACTION",
                "Cannot cancel listing in status " + auction.getStatus());
        }

        adminActionService.record(
            adminUserId,
            AdminActionType.CANCEL_LISTING_FROM_REPORT,
            AdminActionTargetType.LISTING,
            auctionId,
            notes,
            SOURCE_METADATA
        );
    }

    @Transactional
    public void reinstate(UUID publicId, Long adminUserId, String notes) {
        Auction auction = resolveOrThrow(publicId);

        try {
            adminAuctionService.reinstate(auction.getId(), Optional.empty());
        } catch (AuctionNotSuspendedException e) {
            throw new AdminListingStateException(
                "NOT_SUSPENDED",
                "Listing is currently " + e.getCurrentStatus() + ", not SUSPENDED");
        } catch (AuctionNotFoundException e) {
            throw new AdminListingStateException("LISTING_NOT_FOUND", e.getMessage());
        }

        adminActionService.record(
            adminUserId,
            AdminActionType.REINSTATE_LISTING,
            AdminActionTargetType.LISTING,
            auction.getId(),
            notes,
            SOURCE_METADATA
        );
    }

    /**
     * Toggles the auction's {@code is_featured} flag (and, when featuring,
     * its {@code featured_until} expiry). Evicts the FEATURED rail's Redis
     * cache so the homepage surfaces the change immediately rather than
     * waiting up to 60s for the TTL.
     *
     * <p>Status guard is defense-in-depth: the admin row-action menu
     * already gates by {@code ACTIVE}, but a direct API hit must also fail
     * cleanly. {@code featured = false} with {@code featuredUntil} populated
     * is a caller bug — surface it rather than silently dropping the field.
     */
    @Transactional
    public AdminListingRowDto setFeatured(UUID publicId, Long adminUserId, SetFeaturedRequest req) {
        Auction auction = resolveOrThrow(publicId);

        if (auction.getStatus() != AuctionStatus.ACTIVE) {
            throw new AdminListingStateException(
                "FEATURE_REQUIRES_ACTIVE_STATUS",
                "Cannot feature a listing in status " + auction.getStatus());
        }
        if (!req.featured() && req.featuredUntil() != null) {
            throw new AdminListingStateException(
                "FEATURED_UNTIL_REQUIRES_FEATURED_TRUE",
                "featuredUntil cannot be set when featured=false");
        }

        auction.setFeatured(req.featured());
        auction.setFeaturedUntil(req.featured() ? req.featuredUntil() : null);
        auctionRepo.save(auction);

        featuredCache.invalidate(FeaturedCategory.FEATURED);

        adminActionService.record(
            adminUserId,
            req.featured() ? AdminActionType.FEATURE_LISTING
                           : AdminActionType.UNFEATURE_LISTING,
            AdminActionTargetType.LISTING,
            auction.getId(),
            null,
            SOURCE_METADATA);

        return queryRepo.findRowByPublicId(publicId)
            .orElseThrow(() -> new AdminListingStateException(
                "LISTING_NOT_FOUND",
                "Listing not found after write: " + publicId));
    }

    private Auction resolveOrThrow(UUID publicId) {
        return auctionRepo.findByPublicId(publicId)
            .orElseThrow(() -> new AdminListingStateException(
                "LISTING_NOT_FOUND",
                "Listing not found: " + publicId));
    }
}
