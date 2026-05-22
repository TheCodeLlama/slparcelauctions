package com.slparcelauctions.backend.auction;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import com.slparcelauctions.backend.storage.ObjectStorageService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Fetches the parcel snapshot image from Second Life and stores it as an
 * {@link AuctionPhoto} with {@link PhotoSource#SL_PARCEL_SNAPSHOT}. At most
 * one such row per auction — subsequent calls upsert in place.
 *
 * <p>All network / size failures are swallowed and logged at INFO; callers
 * should treat this as a best-effort side-effect. The auction row is never
 * rolled back because of a snapshot photo failure.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParcelSnapshotPhotoService {

    private static final int MAX_BYTES = 5 * 1024 * 1024; // 5 MB
    private static final String CONTENT_TYPE = "image/jpeg";

    private final WebClient.Builder webClientBuilder;
    private final AuctionPhotoRepository photoRepo;
    private final ObjectStorageService storage;
    private final AuctionConfigProperties config;

    @Transactional
    public void refreshFor(Auction auction, String slSnapshotUrl) {
        if (slSnapshotUrl == null || slSnapshotUrl.isBlank()) {
            return;
        }

        byte[] bytes;
        try {
            bytes = webClientBuilder.build()
                    .get()
                    .uri(slSnapshotUrl)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .timeout(config.snapshotFetchTimeout())
                    .block();
        } catch (Exception e) {
            log.info("SL snapshot fetch failed for auction {}: {}", auction.getId(), e.getMessage());
            return;
        }

        if (bytes == null || bytes.length == 0) {
            log.info("SL snapshot fetch returned empty body for auction {}", auction.getId());
            return;
        }

        if (bytes.length > MAX_BYTES) {
            log.info("SL snapshot oversized for auction {}: {} bytes (limit {})", auction.getId(), bytes.length, MAX_BYTES);
            return;
        }

        AuctionPhoto photo = photoRepo
                .findFirstByAuctionIdAndSource(auction.getId(), PhotoSource.SL_PARCEL_SNAPSHOT)
                .orElse(null);

        String objectKey;
        if (photo != null) {
            // Update existing — snapshot service only manages the LIGHT slot
            // (SL World API has no awareness of theme variants).
            photo.setLightContentType(CONTENT_TYPE);
            photo.setLightSizeBytes((long) bytes.length);
            photo.setUploadedAt(OffsetDateTime.now());
            objectKey = photo.getLightObjectKey();
            photo = photoRepo.save(photo);
        } else {
            // Insert new — claim the next-available sortOrder. Empty auction =>
            // 0; if a USER_DEFAULT_COVER row already landed at 0, this becomes
            // 1; etc. Auto-insert services run before any seller upload, so the
            // gap between auto-inserts and seller uploads stays clean.
            int sortOrder = photoRepo.findMaxSortOrderByAuctionId(auction.getId()) + 1;
            objectKey = "listings/" + auction.getId() + "/sl-snapshot.jpg";
            photo = AuctionPhoto.builder()
                    .auction(auction)
                    .lightObjectKey(objectKey)
                    .lightContentType(CONTENT_TYPE)
                    .lightSizeBytes((long) bytes.length)
                    .sortOrder(sortOrder)
                    .source(PhotoSource.SL_PARCEL_SNAPSHOT)
                    .build();
            photo = photoRepo.save(photo);
        }

        storage.put(objectKey, bytes, CONTENT_TYPE);
        log.info("SL snapshot saved for auction {}: photoId={} bytes={}", auction.getId(), photo.getId(), bytes.length);
    }
}
