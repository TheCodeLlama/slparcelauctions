package com.slparcelauctions.backend.auction;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.user.User;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Auto-inserts a default cover image as the first photo of a newly-created
 * auction. Runs at draft creation only; once the row exists in
 * {@code auction_photos} it is owned by the listing (reorderable, removable).
 * Subsequent changes to the underlying user/group default cover do not
 * propagate.
 *
 * <p>Source-selection rule (Plan Task 5 of the theme-image-variants feature):
 * <ol>
 *   <li>If the auction is group-owned ({@code realtyGroupId != null}) and that
 *       group has at least one default-listing variant set, use the group
 *       source ({@link PhotoSource#GROUP_DEFAULT_COVER}).</li>
 *   <li>Otherwise, if the seller has at least one default-cover variant set,
 *       use the user source ({@link PhotoSource#USER_DEFAULT_COVER}).</li>
 *   <li>Otherwise no-op.</li>
 * </ol>
 *
 * <p>Variant-copy rule: both the LIGHT and DARK source object keys are copied
 * into per-listing keys via the object-storage server-side copy when present.
 * The {@code light_*} columns on {@link AuctionPhoto} are NOT NULL; if a source
 * has only the dark variant, it is promoted into the light slot so the row
 * inserts successfully (a dark-only viewer still sees the right image because
 * the renderer falls back to the light slot when no dark exists).
 *
 * <p>{@link #applyTo(Auction)} is best-effort and idempotent:
 * <ul>
 *   <li>If a {@code USER_DEFAULT_COVER} or {@code GROUP_DEFAULT_COVER} row
 *       already exists for this auction it is a no-op (defends against
 *       accidental double-invoke).</li>
 *   <li>If S3 copy or row insert fails the failure is logged at WARN level
 *       and the method returns. The auction row is unaffected; same
 *       fail-soft posture as {@link ParcelSnapshotPhotoService}.</li>
 * </ul>
 *
 * <p>SortOrder is hardcoded to {@code 0}: this service runs before
 * {@code ParcelSnapshotPhotoService.refreshFor}, which now claims the next
 * available slot ({@code MAX(sortOrder) + 1}). The result is cover at 0,
 * snapshot at 1 (or 0 if no cover ran), seller uploads at 2+ / 1+ / 0+.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultCoverPhotoService {

    private final AuctionPhotoRepository auctionPhotoRepository;
    private final ObjectStorageService objectStorage;
    private final RealtyGroupRepository realtyGroupRepository;

    @Transactional
    public void applyTo(Auction auction) {
        Long auctionId = auction.getId();
        if (auctionPhotoRepository.existsByAuctionIdAndSource(auctionId, PhotoSource.USER_DEFAULT_COVER)
                || auctionPhotoRepository.existsByAuctionIdAndSource(auctionId, PhotoSource.GROUP_DEFAULT_COVER)) {
            return;
        }

        DefaultCoverSource src = pickSource(auction);
        if (src == null) {
            return;
        }

        String lightDst = null;
        String darkDst = null;

        if (src.lightObjectKey() != null) {
            lightDst = "listings/" + auctionId + "/" + UUID.randomUUID()
                    + extensionOf(src.lightObjectKey());
            try {
                objectStorage.copy(src.lightObjectKey(), lightDst);
            } catch (Exception e) {
                log.warn("Failed to copy light default-cover for auction {} (source {}): {}",
                        auctionId, src.photoSource(), e.getMessage());
                return;
            }
        }

        if (src.darkObjectKey() != null) {
            darkDst = "listings/" + auctionId + "/" + UUID.randomUUID()
                    + extensionOf(src.darkObjectKey());
            try {
                objectStorage.copy(src.darkObjectKey(), darkDst);
            } catch (Exception e) {
                log.warn("Failed to copy dark default-cover for auction {} (source {}): {}",
                        auctionId, src.photoSource(), e.getMessage());
                // Light already succeeded if set; proceed with light only.
                darkDst = null;
            }
        }

        AuctionPhoto.AuctionPhotoBuilder<?, ?> builder = AuctionPhoto.builder()
                .auction(auction)
                .sortOrder(0)
                .source(src.photoSource());

        // The light_* columns on AuctionPhoto are NOT NULL. If only the dark
        // variant was provided (and copied successfully), promote it into the
        // light slot so the row inserts. The themed renderer's fallback rule
        // (light shown when dark missing) means dark-only viewers still see
        // the correct image.
        if (lightDst == null && darkDst != null) {
            builder.lightObjectKey(darkDst)
                    .lightContentType(src.darkContentType())
                    .lightSizeBytes(src.darkSizeBytes());
        } else if (lightDst != null) {
            builder.lightObjectKey(lightDst)
                    .lightContentType(src.lightContentType())
                    .lightSizeBytes(src.lightSizeBytes());
            if (darkDst != null) {
                builder.darkObjectKey(darkDst)
                        .darkContentType(src.darkContentType())
                        .darkSizeBytes(src.darkSizeBytes());
            }
        } else {
            // Neither variant copied successfully. Nothing to insert.
            return;
        }

        try {
            auctionPhotoRepository.save(builder.build());
            log.info("Default cover applied to auction {} (source {}): lightKey={}, darkKey={}",
                    auctionId, src.photoSource(), lightDst, darkDst);
        } catch (Exception e) {
            log.warn("Failed to insert default-cover row for auction {} (source {}): {}",
                    auctionId, src.photoSource(), e.getMessage());
        }
    }

    private DefaultCoverSource pickSource(Auction auction) {
        // Group-owned listings prefer the group's default-listing image. We
        // resolve the group through the repo (rather than a lazy navigation
        // off the auction) because Auction carries the group as a bare Long
        // FK, not a @ManyToOne.
        if (auction.getRealtyGroupId() != null) {
            RealtyGroup group = realtyGroupRepository.findById(auction.getRealtyGroupId()).orElse(null);
            if (group != null
                    && (group.getDefaultListingLightObjectKey() != null
                            || group.getDefaultListingDarkObjectKey() != null)) {
                return DefaultCoverSource.fromGroup(group);
            }
        }
        User seller = auction.getSeller();
        if (seller != null
                && (seller.getDefaultCoverLightObjectKey() != null
                        || seller.getDefaultCoverDarkObjectKey() != null)) {
            return DefaultCoverSource.fromUser(seller);
        }
        return null;
    }

    /** Returns ".webp" / ".jpg" / etc. including the leading dot, or "" if the key has no extension. */
    private static String extensionOf(String key) {
        int dot = key.lastIndexOf('.');
        if (dot < 0 || dot == key.length() - 1) {
            return "";
        }
        return key.substring(dot);
    }

    /**
     * Internal carrier struct so the picker can return either flavour of source
     * with a single shape. Mirrors the {@code light_*} / {@code dark_*} layout
     * on both {@link User} and {@link RealtyGroup}.
     */
    private record DefaultCoverSource(
            PhotoSource photoSource,
            String lightObjectKey,
            String lightContentType,
            Long lightSizeBytes,
            String darkObjectKey,
            String darkContentType,
            Long darkSizeBytes) {

        static DefaultCoverSource fromUser(User u) {
            return new DefaultCoverSource(
                    PhotoSource.USER_DEFAULT_COVER,
                    u.getDefaultCoverLightObjectKey(),
                    u.getDefaultCoverLightContentType(),
                    u.getDefaultCoverLightSizeBytes(),
                    u.getDefaultCoverDarkObjectKey(),
                    u.getDefaultCoverDarkContentType(),
                    u.getDefaultCoverDarkSizeBytes());
        }

        static DefaultCoverSource fromGroup(RealtyGroup g) {
            return new DefaultCoverSource(
                    PhotoSource.GROUP_DEFAULT_COVER,
                    g.getDefaultListingLightObjectKey(),
                    g.getDefaultListingLightContentType(),
                    g.getDefaultListingLightSizeBytes(),
                    g.getDefaultListingDarkObjectKey(),
                    g.getDefaultListingDarkContentType(),
                    g.getDefaultListingDarkSizeBytes());
        }
    }
}
