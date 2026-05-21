package com.slparcelauctions.backend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.media.ImageFormat;
import com.slparcelauctions.backend.media.ImageUploadValidator;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.user.exception.UnsupportedImageFormatException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Unit-ish coverage for the two-phase attachment lifecycle:
 *
 * <ul>
 *   <li>{@code preUpload}: rejects on size / mime / validator, happy path
 *       stores to S3 + caches Redis metadata under {@code support:upload:*}.</li>
 *   <li>{@code promote}: missing key, owner mismatch, over-cap, happy path,
 *       and the cleanup path when the DB save explodes mid-list.</li>
 * </ul>
 *
 * <p>Pattern follows {@code SupportTicketNotificationDispatchTest}:
 * {@code @SpringBootTest} + {@code @ActiveProfiles("dev")} so a real
 * {@link StringRedisTemplate} is wired against the dev Redis. The storage
 * service and image validator are mocked via {@link MockitoBean} so the
 * tests don't actually hit S3 / decode images.
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
        "slpa.notifications.sl-im.cleanup.enabled=false",
        "slpa.realty.invitation-expiry.enabled=false"
})
@Transactional
class SupportTicketAttachmentServiceTest {

    @Autowired SupportTicketAttachmentService service;
    @Autowired StringRedisTemplate redis;
    @Autowired UserRepository userRepo;

    // Local mapper for fixture seeding -- mirrors the service-side mapper
    // (also self-managed there). Test fixtures use the same flat-record
    // payload, no Spring bean wiring needed.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean ObjectStorageService storage;
    @MockitoBean ImageUploadValidator imageValidator;

    @PersistenceContext EntityManager em;

    private User uploader;
    private final List<String> redisKeysToClean = new java.util.ArrayList<>();

    @BeforeEach
    void setup() {
        uploader = userRepo.save(User.builder()
                .username("uploader-" + shortId())
                .email("uploader-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("Uploader").build());

        // Default: validator returns a valid 800x600 result. Individual tests
        // can override by re-stubbing.
        when(imageValidator.validate(any(byte[].class), anyLong(), anyInt()))
                .thenReturn(new ImageUploadValidator.ValidationResult(
                        ImageFormat.PNG,
                        new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB),
                        800, 600));
    }

    @AfterEach
    void cleanup() {
        for (String k : redisKeysToClean) {
            redis.delete(k);
        }
        redisKeysToClean.clear();
    }

    // ── preUpload ─────────────────────────────────────────────────────────────

    @Test
    void preUpload_rejects_over_size_limit() {
        byte[] huge = new byte[6 * 1024 * 1024]; // > default 5MB cap
        MockMultipartFile file = new MockMultipartFile(
                "file", "big.png", "image/png", huge);

        assertThatThrownBy(() -> service.preUpload(uploader, file))
                .isInstanceOf(SupportTicketException.class)
                .extracting("code").isEqualTo(SupportTicketError.INVALID_ATTACHMENT);

        verify(storage, never()).put(any(), any(), any());
    }

    @Test
    void preUpload_rejects_disallowed_mime() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.exe", "application/x-msdownload", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.preUpload(uploader, file))
                .isInstanceOf(SupportTicketException.class)
                .extracting("code").isEqualTo(SupportTicketError.INVALID_ATTACHMENT);

        verify(storage, never()).put(any(), any(), any());
    }

    @Test
    void preUpload_validator_failure_throws_INVALID_ATTACHMENT() {
        when(imageValidator.validate(any(byte[].class), anyLong(), anyInt()))
                .thenThrow(new UnsupportedImageFormatException("bad bytes"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "lying.png", "image/png", new byte[]{0, 1, 2});

        assertThatThrownBy(() -> service.preUpload(uploader, file))
                .isInstanceOf(SupportTicketException.class)
                .extracting("code").isEqualTo(SupportTicketError.INVALID_ATTACHMENT);

        verify(storage, never()).put(any(), any(), any());
    }

    @Test
    void preUpload_happy_path_returns_key_and_caches_in_redis() throws Exception {
        byte[] bytes = new byte[]{10, 20, 30, 40};
        MockMultipartFile file = new MockMultipartFile(
                "file", "ok.png", "image/png", bytes);

        String attachmentKey = service.preUpload(uploader, file);
        redisKeysToClean.add("support:upload:" + attachmentKey);

        assertThat(attachmentKey).isNotBlank();
        // Returned key is a UUID, not the storage path
        assertThat(UUID.fromString(attachmentKey)).isNotNull();

        // S3 put fired with the expected pending path
        String expectedPath = "support-attachments/pending/" + uploader.getPublicId()
                + "/" + attachmentKey + ".png";
        verify(storage).put(eq(expectedPath), eq(bytes), eq("image/png"));

        // Redis entry is present with the pending metadata
        String json = redis.opsForValue().get("support:upload:" + attachmentKey);
        assertThat(json).isNotNull();
        SupportTicketAttachmentService.PendingAttachment p =
                objectMapper.readValue(json, SupportTicketAttachmentService.PendingAttachment.class);
        assertThat(p.userId()).isEqualTo(uploader.getId());
        assertThat(p.storageKey()).isEqualTo(expectedPath);
        assertThat(p.mime()).isEqualTo("image/png");
        assertThat(p.size()).isEqualTo(bytes.length);
        assertThat(p.width()).isEqualTo(800);
        assertThat(p.height()).isEqualTo(600);
    }

    // ── promote ───────────────────────────────────────────────────────────────

    @Test
    void promote_unknown_key_throws_ATTACHMENT_NOT_FOUND() {
        SupportTicketMessage message = persistTicketAndMessage();
        SupportTicketAttachmentRepository repoMock = mock(SupportTicketAttachmentRepository.class);

        String bogus = UUID.randomUUID().toString();

        assertThatThrownBy(() -> service.promote(
                List.of(bogus), uploader.getId(), message, repoMock))
                .isInstanceOf(SupportTicketException.class)
                .extracting("code").isEqualTo(SupportTicketError.ATTACHMENT_NOT_FOUND);
    }

    @Test
    void promote_owner_mismatch_throws_NOT_OWNER() throws Exception {
        SupportTicketMessage message = persistTicketAndMessage();
        SupportTicketAttachmentRepository repoMock = mock(SupportTicketAttachmentRepository.class);

        String key = seedPendingAttachment(uploader.getId(),
                "support-attachments/pending/x/abc.png", "image/png", 1024, 800, 600);

        // Promote as a different owner id
        assertThatThrownBy(() -> service.promote(
                List.of(key), uploader.getId() + 999L, message, repoMock))
                .isInstanceOf(SupportTicketException.class)
                .extracting("code").isEqualTo(SupportTicketError.NOT_OWNER);
    }

    @Test
    void promote_over_max_throws_INVALID_ATTACHMENT() {
        SupportTicketMessage message = persistTicketAndMessage();
        SupportTicketAttachmentRepository repoMock = mock(SupportTicketAttachmentRepository.class);

        List<String> tooMany = List.of(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString());

        assertThatThrownBy(() -> service.promote(
                tooMany, uploader.getId(), message, repoMock))
                .isInstanceOf(SupportTicketException.class)
                .extracting("code").isEqualTo(SupportTicketError.INVALID_ATTACHMENT);
    }

    @Test
    void promote_happy_path_copies_and_inserts_row() throws Exception {
        SupportTicketMessage message = persistTicketAndMessage();
        SupportTicketAttachmentRepository repoMock = mock(SupportTicketAttachmentRepository.class);
        when(repoMock.save(any(SupportTicketAttachment.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        String pendingKey = "support-attachments/pending/u/abc.png";
        String key = seedPendingAttachment(uploader.getId(),
                pendingKey, "image/png", 4096, 800, 600);

        List<SupportTicketAttachment> result = service.promote(
                List.of(key), uploader.getId(), message, repoMock);

        assertThat(result).hasSize(1);
        String expectedPromotedKey = "support-attachments/" + message.getId() + "/" + key;
        SupportTicketAttachment att = result.get(0);
        assertThat(att.getStorageKey()).isEqualTo(expectedPromotedKey);
        assertThat(att.getMimeType()).isEqualTo("image/png");
        assertThat(att.getSizeBytes()).isEqualTo(4096);
        assertThat(att.getWidth()).isEqualTo(800);
        assertThat(att.getHeight()).isEqualTo(600);

        verify(storage).copy(pendingKey, expectedPromotedKey);
        verify(storage).delete(pendingKey);
        verify(repoMock).save(any(SupportTicketAttachment.class));

        // Redis entry cleared
        assertThat(redis.opsForValue().get("support:upload:" + key)).isNull();
    }

    @Test
    void promote_db_failure_after_copy_cleans_up_promoted_object() throws Exception {
        SupportTicketMessage message = persistTicketAndMessage();
        SupportTicketAttachmentRepository repoMock = mock(SupportTicketAttachmentRepository.class);
        // First save succeeds, second blows up
        when(repoMock.save(any(SupportTicketAttachment.class)))
                .thenAnswer(inv -> inv.getArgument(0))
                .thenThrow(new RuntimeException("DB exploded"));

        String key1 = seedPendingAttachment(uploader.getId(),
                "support-attachments/pending/u/one.png", "image/png", 1024, 100, 100);
        String key2 = seedPendingAttachment(uploader.getId(),
                "support-attachments/pending/u/two.png", "image/png", 2048, 200, 200);

        String promoted1 = "support-attachments/" + message.getId() + "/" + key1;
        String promoted2 = "support-attachments/" + message.getId() + "/" + key2;

        assertThatThrownBy(() -> service.promote(
                List.of(key1, key2), uploader.getId(), message, repoMock))
                .isInstanceOf(RuntimeException.class);

        // Both objects were copied
        verify(storage).copy("support-attachments/pending/u/one.png", promoted1);
        verify(storage).copy("support-attachments/pending/u/two.png", promoted2);

        // Cleanup ran: both promoted keys were deleted as part of the catch
        // block. (The first pending key was also deleted in the happy-path
        // delete-after-copy step, so we just assert the promoted-key deletes
        // ran at least once each.)
        verify(storage, atLeastOnce()).delete(promoted1);
        verify(storage, atLeastOnce()).delete(promoted2);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private SupportTicketMessage persistTicketAndMessage() {
        OffsetDateTime now = OffsetDateTime.now();
        SupportTicket ticket = SupportTicket.builder()
                .user(uploader)
                .subject("subj-" + shortId())
                .category(SupportTicketCategory.OTHER)
                .status(SupportTicketStatus.OPEN)
                .lastMessageAt(now)
                .lastMessageAuthor(SupportTicketAuthorRole.USER)
                .build();
        em.persist(ticket);
        SupportTicketMessage message = SupportTicketMessage.builder()
                .ticket(ticket)
                .authorUser(uploader)
                .authorRole(SupportTicketAuthorRole.USER)
                .body("body-" + shortId())
                .visibleToUser(true)
                .build();
        em.persist(message);
        em.flush();
        return message;
    }

    private String seedPendingAttachment(long userId, String storageKey,
            String mime, int size, int width, int height) throws Exception {
        String key = UUID.randomUUID().toString();
        SupportTicketAttachmentService.PendingAttachment p =
                new SupportTicketAttachmentService.PendingAttachment(
                        userId, storageKey, mime, size, width, height);
        String json = objectMapper.writeValueAsString(p);
        redis.opsForValue().set("support:upload:" + key, json, Duration.ofMinutes(5));
        redisKeysToClean.add("support:upload:" + key);
        return key;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
