package com.slparcelauctions.backend.support;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.media.ImageUploadValidator;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.user.User;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Two-phase attachment lifecycle for {@link SupportTicketMessage}:
 *
 * <ol>
 *   <li>{@link #preUpload(User, MultipartFile)} stages the file at
 *       {@code support-attachments/pending/{userPublicId}/{attachmentKey}.{ext}}
 *       and caches per-upload metadata in Redis under
 *       {@code support:upload:{attachmentKey}} with a TTL so an abandoned
 *       upload self-cleans without needing a sweeper. The returned
 *       {@code attachmentKey} is an opaque UUID, not the storage key, so the
 *       client never learns the bucket layout.</li>
 *   <li>{@link #promote(List, long, SupportTicketMessage,
 *       SupportTicketAttachmentRepository)} runs at message create / reply
 *       time. For each key it: re-reads Redis, asserts ownership, copies
 *       the object to the message-scoped path
 *       {@code support-attachments/{messageId}/{attachmentKey}}, deletes the
 *       pending object, inserts the {@link SupportTicketAttachment} row, and
 *       deletes the Redis entry. On any failure mid-list it best-effort
 *       deletes the promoted-but-uncommitted S3 objects so a partial promote
 *       does not leak orphaned bytes.</li>
 * </ol>
 *
 * <p>{@link #signedDownloadUrl(SupportTicketAttachment)} mints a short-lived
 * presigned GET URL for the read path; attachments are private and require
 * the caller's principal to have access to the parent ticket (enforced at
 * the controller layer).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupportTicketAttachmentService {

    private final ObjectStorageService storage;
    private final ImageUploadValidator imageValidator;
    private final StringRedisTemplate redis;

    // Self-managed: the pending-attachment payload is a flat record with
    // primitive fields, so we don't need any Spring-side Jackson modules and
    // can avoid relying on the auto-configured ObjectMapper bean.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${slpa.support.attachments.max-file-bytes:5242880}")
    private long maxFileBytes;

    @Value("${slpa.support.attachments.allowed-mime-types:image/png,image/jpeg,image/webp,image/gif}")
    private String allowedMimeTypesCsv;

    @Value("${slpa.support.attachments.max-per-message:3}")
    private int maxPerMessage;

    @Value("${slpa.support.attachments.pending-ttl-seconds:3600}")
    private long pendingTtlSeconds;

    private static final int MAX_DIMENSION = 4096;

    static final String REDIS_KEY_PREFIX = "support:upload:";

    public String preUpload(User uploader, MultipartFile file) {
        if (file.getSize() > maxFileBytes) {
            throw new SupportTicketException(SupportTicketError.INVALID_ATTACHMENT,
                    "file exceeds " + maxFileBytes + " bytes");
        }
        List<String> allowed = Arrays.asList(allowedMimeTypesCsv.split(","));
        if (!allowed.contains(file.getContentType())) {
            throw new SupportTicketException(SupportTicketError.INVALID_ATTACHMENT,
                    "mime " + file.getContentType() + " not in allowlist");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new SupportTicketException(SupportTicketError.INVALID_ATTACHMENT,
                    "could not read file bytes");
        }
        ImageUploadValidator.ValidationResult dims;
        try {
            dims = imageValidator.validate(bytes, maxFileBytes, MAX_DIMENSION);
        } catch (RuntimeException ex) {
            throw new SupportTicketException(SupportTicketError.INVALID_ATTACHMENT,
                    "image validation failed: " + ex.getMessage());
        }
        String ext = switch (file.getContentType()) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "bin";
        };
        String attachmentKey = UUID.randomUUID().toString();
        String storageKey = "support-attachments/pending/" + uploader.getPublicId()
                + "/" + attachmentKey + "." + ext;
        storage.put(storageKey, bytes, file.getContentType());

        PendingAttachment pending = new PendingAttachment(
                uploader.getId(), storageKey, file.getContentType(),
                (int) file.getSize(), dims.width(), dims.height());
        try {
            String json = objectMapper.writeValueAsString(pending);
            redis.opsForValue().set(REDIS_KEY_PREFIX + attachmentKey, json,
                    Duration.ofSeconds(pendingTtlSeconds));
        } catch (Exception e) {
            try { storage.delete(storageKey); } catch (Exception ignored) { /* best effort */ }
            throw new RuntimeException("failed to cache pending attachment", e);
        }
        return attachmentKey;
    }

    @Transactional
    public List<SupportTicketAttachment> promote(
            List<String> attachmentKeys, long expectedOwnerId,
            SupportTicketMessage targetMessage,
            SupportTicketAttachmentRepository repo) {
        if (attachmentKeys == null || attachmentKeys.isEmpty()) {
            return List.of();
        }
        if (attachmentKeys.size() > maxPerMessage) {
            throw new SupportTicketException(SupportTicketError.INVALID_ATTACHMENT,
                    "max " + maxPerMessage + " attachments per message");
        }
        List<String> promotedKeysForCleanup = new ArrayList<>();
        try {
            List<SupportTicketAttachment> result = new ArrayList<>();
            for (String key : attachmentKeys) {
                String json = redis.opsForValue().get(REDIS_KEY_PREFIX + key);
                if (json == null) {
                    throw new SupportTicketException(SupportTicketError.ATTACHMENT_NOT_FOUND,
                            "attachment " + key + " missing or expired");
                }
                PendingAttachment p;
                try {
                    p = objectMapper.readValue(json, PendingAttachment.class);
                } catch (Exception e) {
                    throw new SupportTicketException(SupportTicketError.ATTACHMENT_NOT_FOUND,
                            "attachment " + key + " metadata corrupt");
                }
                if (p.userId() != expectedOwnerId) {
                    throw new SupportTicketException(SupportTicketError.NOT_OWNER,
                            "attachment " + key + " not owned by caller");
                }
                String promotedKey = "support-attachments/" + targetMessage.getId() + "/" + key;
                storage.copy(p.storageKey(), promotedKey);
                promotedKeysForCleanup.add(promotedKey);
                try { storage.delete(p.storageKey()); } catch (Exception ignored) { /* best effort */ }
                SupportTicketAttachment att = SupportTicketAttachment.builder()
                        .message(targetMessage)
                        .storageKey(promotedKey)
                        .mimeType(p.mime())
                        .sizeBytes(p.size())
                        .width(p.width())
                        .height(p.height())
                        .build();
                result.add(repo.save(att));
                redis.delete(REDIS_KEY_PREFIX + key);
            }
            return result;
        } catch (RuntimeException ex) {
            for (String k : promotedKeysForCleanup) {
                try { storage.delete(k); } catch (Exception ignored) { /* best effort */ }
            }
            throw ex;
        }
    }

    public String signedDownloadUrl(SupportTicketAttachment att) {
        return storage.presignGet(att.getStorageKey(), Duration.ofMinutes(5));
    }

    /**
     * Pending-upload metadata cached in Redis between the pre-upload and
     * promote steps. Package-visible so the test suite can construct +
     * serialize fixtures with the same JSON shape the service consumes.
     */
    record PendingAttachment(long userId, String storageKey, String mime,
                              int size, int width, int height) {}
}
