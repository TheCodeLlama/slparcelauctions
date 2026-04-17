package com.slparcelauctions.backend.auction;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auction.exception.InvalidAuctionStateException;
import com.slparcelauctions.backend.auction.exception.PhotoLimitExceededException;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.storage.StoredObject;
import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrator for per-auction photo uploads. Accepts a
 * {@link MultipartFile}, validates + re-encodes via
 * {@link ListingPhotoProcessor} (strips metadata), stores the processed
 * bytes at {@code listings/{auctionId}/{uuid}.{ext}} in object storage,
 * and persists an {@link AuctionPhoto} row. Mutations are gated to the
 * seller and to statuses {@code DRAFT} or {@code DRAFT_PAID}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuctionPhotoService {

    private final AuctionService auctionService;
    private final AuctionPhotoRepository photoRepo;
    private final ListingPhotoProcessor processor;
    private final ObjectStorageService storage;

    @Value("${slpa.photos.max-per-listing:10}")
    private int maxPerListing;

    @Transactional
    public AuctionPhoto upload(Long auctionId, Long sellerId, MultipartFile file) {
        Auction auction = auctionService.loadForSeller(auctionId, sellerId);
        if (auction.getStatus() != AuctionStatus.DRAFT
                && auction.getStatus() != AuctionStatus.DRAFT_PAID) {
            throw new InvalidAuctionStateException(auctionId, auction.getStatus(), "UPLOAD_PHOTO");
        }
        long currentCount = photoRepo.countByAuctionId(auctionId);
        if (currentCount >= maxPerListing) {
            throw new PhotoLimitExceededException((int) currentCount, maxPerListing);
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UnsupportedImageFormatException(
                    "Failed to read upload: " + e.getMessage(), e);
        }

        ListingPhotoProcessor.ProcessedPhoto processed = processor.process(bytes);
        String objectKey = "listings/" + auctionId + "/" + UUID.randomUUID()
                + "." + processed.format().extension();
        storage.put(objectKey, processed.bytes(), processed.format().contentType());

        int nextSort = (int) currentCount + 1;
        AuctionPhoto photo = AuctionPhoto.builder()
                .auction(auction)
                .objectKey(objectKey)
                .contentType(processed.format().contentType())
                .sizeBytes(processed.sizeBytes())
                .sortOrder(nextSort)
                .build();
        AuctionPhoto saved = photoRepo.save(photo);
        log.info("Auction {} photo uploaded: id={} key={} ({} bytes, sortOrder={})",
                auctionId, saved.getId(), objectKey, processed.sizeBytes(), nextSort);
        return saved;
    }

    @Transactional
    public void delete(Long auctionId, Long photoId, Long sellerId) {
        Auction auction = auctionService.loadForSeller(auctionId, sellerId);
        if (auction.getStatus() != AuctionStatus.DRAFT
                && auction.getStatus() != AuctionStatus.DRAFT_PAID) {
            throw new InvalidAuctionStateException(auctionId, auction.getStatus(), "DELETE_PHOTO");
        }
        AuctionPhoto photo = photoRepo.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("Photo not found: " + photoId));
        if (!photo.getAuction().getId().equals(auctionId)) {
            throw new IllegalArgumentException(
                    "Photo " + photoId + " does not belong to auction " + auctionId);
        }
        String expectedPrefix = "listings/" + auctionId + "/";
        if (!photo.getObjectKey().startsWith(expectedPrefix)) {
            throw new IllegalStateException(
                    "Object key mismatch for photo " + photoId + ": " + photo.getObjectKey());
        }
        storage.delete(photo.getObjectKey());
        photoRepo.delete(photo);
        log.info("Auction {} photo deleted: id={} key={}",
                auctionId, photoId, photo.getObjectKey());
    }

    @Transactional(readOnly = true)
    public StoredObject fetchBytes(Long auctionId, Long photoId, Long callerId) {
        AuctionPhoto photo = photoRepo.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("Photo not found: " + photoId));
        Auction auction = photo.getAuction();
        if (!auction.getId().equals(auctionId)) {
            throw new IllegalArgumentException(
                    "Photo " + photoId + " does not belong to auction " + auctionId);
        }
        // Pre-ACTIVE auctions are draft-only: serving their photos publicly would
        // let anyone enumerate draft listings by iterating photo IDs. Mirror the
        // privacy pattern in AuctionController.get() — non-sellers 404 to hide
        // the draft's existence entirely.
        if (isPreActive(auction.getStatus())) {
            boolean isSeller = callerId != null
                    && auction.getSeller().getId().equals(callerId);
            if (!isSeller) {
                throw new AuctionNotFoundException(auctionId);
            }
        }
        return storage.get(photo.getObjectKey());
    }

    private static boolean isPreActive(AuctionStatus s) {
        return s == AuctionStatus.DRAFT
                || s == AuctionStatus.DRAFT_PAID
                || s == AuctionStatus.VERIFICATION_PENDING
                || s == AuctionStatus.VERIFICATION_FAILED;
    }

    @Transactional(readOnly = true)
    public List<AuctionPhoto> list(Long auctionId) {
        return photoRepo.findByAuctionIdOrderBySortOrderAsc(auctionId);
    }
}
