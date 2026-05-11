package com.slparcelauctions.backend.escrow.dispute;

import com.slparcelauctions.backend.escrow.dispute.exception.EvidenceImageContentTypeException;
import com.slparcelauctions.backend.escrow.dispute.exception.EvidenceImageTooLargeException;
import com.slparcelauctions.backend.escrow.dispute.exception.EvidenceTooManyImagesException;
import com.slparcelauctions.backend.storage.ImagePurpose;
import com.slparcelauctions.backend.storage.ImageStorageContext;
import com.slparcelauctions.backend.storage.ImageStorageService;
import com.slparcelauctions.backend.storage.StoredImage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DisputeEvidenceUploadService {

    public static final int MAX_IMAGES_PER_SIDE = 5;
    public static final long MAX_IMAGE_SIZE_BYTES = 5L * 1024 * 1024;
    public static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp");

    private final ImageStorageService imageStorage;
    private final Clock clock;

    public List<EvidenceImage> uploadAll(long escrowId, String role, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        if (files.size() > MAX_IMAGES_PER_SIDE) {
            throw new EvidenceTooManyImagesException(files.size(), MAX_IMAGES_PER_SIDE);
        }
        List<EvidenceImage> result = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            // Pre-check client-supplied content type as a fast-fail gate
            // for obvious non-image uploads (PDFs, SVGs). The chokepoint
            // also magic-byte-sniffs and will reject spoofed types with
            // 415, but this controller-friendly exception preserves the
            // existing error contract for the dispute submit flow.
            String contentType = file.getContentType();
            if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
                throw new EvidenceImageContentTypeException(
                        file.getOriginalFilename(), contentType);
            }
            if (file.getSize() > MAX_IMAGE_SIZE_BYTES) {
                throw new EvidenceImageTooLargeException(
                        file.getOriginalFilename(), file.getSize(), MAX_IMAGE_SIZE_BYTES);
            }
            // Caller key sans extension — the chokepoint appends .webp.
            String keyWithoutExt = "dispute-evidence/" + escrowId + "/" + role + "/"
                    + UUID.randomUUID();
            StoredImage stored;
            try {
                stored = imageStorage.storeImage(
                        new ByteArrayInputStream(file.getBytes()),
                        new ImageStorageContext(
                                ImagePurpose.DISPUTE_EVIDENCE, keyWithoutExt));
            } catch (IOException e) {
                log.error("Failed to read evidence image bytes: {}", file.getOriginalFilename(), e);
                throw new RuntimeException("Failed to read upload bytes", e);
            }
            result.add(new EvidenceImage(
                    stored.objectKey(), stored.contentType(), stored.sizeBytes(),
                    OffsetDateTime.now(clock)));
        }
        return result;
    }
}
