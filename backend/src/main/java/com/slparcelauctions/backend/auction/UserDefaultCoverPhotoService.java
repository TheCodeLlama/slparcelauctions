package com.slparcelauctions.backend.auction;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.storage.StoredObject;
import com.slparcelauctions.backend.user.User;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Auto-inserts the seller's persisted default cover image as the first
 * photo of a newly-created auction. Runs at draft creation only; once the
 * row exists in {@code auction_photos} it's owned by the listing
 * (reorderable, removable). Subsequent changes to the user's default cover
 * do not propagate.
 *
 * <p>{@link #applyTo(Auction)} is best-effort and idempotent:
 * <ul>
 *   <li>If the seller has no default cover set → no-op return.</li>
 *   <li>If a {@code USER_DEFAULT_COVER} row already exists for this auction
 *       → no-op return (defends against accidental double-invoke).</li>
 *   <li>If the S3 copy or row insert fails → log warn, return. The auction
 *       row is unaffected. Same fail-soft posture as
 *       {@link ParcelSnapshotPhotoService}.</li>
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
public class UserDefaultCoverPhotoService {

    private final AuctionPhotoRepository auctionPhotoRepository;
    private final ObjectStorageService objectStorage;

    @Transactional
    public void applyTo(Auction auction) {
        Long auctionId = auction.getId();
        if (auctionPhotoRepository.existsByAuctionIdAndSource(auctionId, PhotoSource.USER_DEFAULT_COVER)) {
            return;
        }
        User seller = auction.getSeller();
        if (seller == null || !seller.hasDefaultCover()) {
            return;
        }
        String sourceKey = seller.getDefaultCoverObjectKey();
        String contentType = seller.getDefaultCoverContentType();
        Long sizeBytes = seller.getDefaultCoverSizeBytes();

        try {
            StoredObject src = objectStorage.get(sourceKey);
            String ext = sourceKey.substring(sourceKey.lastIndexOf('.') + 1);
            String destKey = "listings/" + auctionId + "/" + UUID.randomUUID() + "." + ext;
            objectStorage.put(destKey, src.bytes(), contentType);

            AuctionPhoto photo = AuctionPhoto.builder()
                    .auction(auction)
                    .source(PhotoSource.USER_DEFAULT_COVER)
                    .objectKey(destKey)
                    .contentType(contentType)
                    .sizeBytes(sizeBytes)
                    .sortOrder(0)
                    .build();
            auctionPhotoRepository.save(photo);
            log.info("Default cover applied to auction {} (seller {}): destKey={}",
                    auctionId, seller.getId(), destKey);
        } catch (Exception e) {
            log.warn("Failed to apply default cover for auction {} (seller {}): {}",
                    auctionId, seller.getId(), e.getMessage());
        }
    }
}
