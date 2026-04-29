package com.slparcelauctions.backend.escrow.dispute;

import com.slparcelauctions.backend.escrow.dispute.exception.EvidenceImageContentTypeException;
import com.slparcelauctions.backend.escrow.dispute.exception.EvidenceImageTooLargeException;
import com.slparcelauctions.backend.escrow.dispute.exception.EvidenceTooManyImagesException;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

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

    private final ObjectStorageService storage;
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
            String contentType = file.getContentType();
            if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
                throw new EvidenceImageContentTypeException(
                        file.getOriginalFilename(), contentType);
            }
            if (file.getSize() > MAX_IMAGE_SIZE_BYTES) {
                throw new EvidenceImageTooLargeException(
                        file.getOriginalFilename(), file.getSize(), MAX_IMAGE_SIZE_BYTES);
            }
            String ext = extensionFor(contentType);
            String key = "dispute-evidence/" + escrowId + "/" + role + "/"
                    + UUID.randomUUID() + "." + ext;
            try {
                storage.put(key, file.getBytes(), contentType);
            } catch (IOException e) {
                log.error("Failed to read evidence image bytes: {}", file.getOriginalFilename(), e);
                throw new RuntimeException("Failed to read upload bytes", e);
            }
            result.add(new EvidenceImage(
                    key, contentType, file.getSize(), OffsetDateTime.now(clock)));
        }
        return result;
    }

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            default -> "bin";
        };
    }
}
