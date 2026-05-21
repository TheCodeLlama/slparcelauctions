package com.slparcelauctions.backend.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.media.ImageFormat;
import com.slparcelauctions.backend.media.ImageUploadValidator;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Integration coverage for {@link SupportTicketAttachmentController}. Spins
 * the full Spring context (auth chain + slice advice + JPA) and exercises
 * both endpoints via {@link MockMvc} with real user / admin JWTs.
 *
 * <p>{@link ObjectStorageService} and {@link ImageUploadValidator} are
 * mocked so the tests never hit S3 / actually decode pixels. The signed-URL
 * test seeds a real attachment row inside the test transaction.
 */
@SpringBootTest
@AutoConfigureMockMvc
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
class SupportTicketAttachmentControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepo;
    @Autowired SupportTicketRepository ticketRepo;
    @Autowired SupportTicketMessageRepository messageRepo;
    @Autowired SupportTicketAttachmentRepository attachmentRepo;
    @PersistenceContext EntityManager em;

    @MockitoBean ObjectStorageService storage;
    @MockitoBean ImageUploadValidator imageValidator;

    private User user;
    private User otherUser;
    private User admin;
    private String userJwt;
    private String otherJwt;
    private String adminJwt;

    @BeforeEach
    void seed() {
        user = userRepo.save(User.builder()
                .username("user-" + shortId())
                .email("user-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("User").role(Role.USER).verified(true).build());
        otherUser = userRepo.save(User.builder()
                .username("other-" + shortId())
                .email("other-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("Other").role(Role.USER).verified(true).build());
        admin = userRepo.save(User.builder()
                .username("admin-" + shortId())
                .email("admin-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x").slAvatarUuid(UUID.randomUUID())
                .displayName("Admin").role(Role.ADMIN).verified(true).build());

        userJwt = jwtService.issueAccessToken(new AuthPrincipal(
                user.getId(), user.getPublicId(), user.getEmail(), 0L, Role.USER));
        otherJwt = jwtService.issueAccessToken(new AuthPrincipal(
                otherUser.getId(), otherUser.getPublicId(), otherUser.getEmail(),
                0L, Role.USER));
        adminJwt = jwtService.issueAccessToken(new AuthPrincipal(
                admin.getId(), admin.getPublicId(), admin.getEmail(), 0L, Role.ADMIN));

        // Default: validator returns a valid 800x600 result. Individual tests
        // can override.
        when(imageValidator.validate(any(byte[].class), anyLong(), anyInt()))
                .thenReturn(new ImageUploadValidator.ValidationResult(
                        ImageFormat.PNG,
                        new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB),
                        800, 600));
    }

    /* ============================================================ */
    /* POST /api/v1/me/support-tickets/attachments                  */
    /* ============================================================ */

    @Test
    void preUpload_happyPath_returnsAttachmentKey() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "ok.png", "image/png", new byte[]{1, 2, 3, 4});

        mockMvc.perform(multipart("/api/v1/me/support-tickets/attachments")
                        .file(file)
                        .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachmentKey").isNotEmpty());
    }

    @Test
    void preUpload_rejectsOverSize_400_INVALID_ATTACHMENT() throws Exception {
        // 6 MiB > default 5 MiB cap. The default Spring multipart cap is
        // 25 MiB (application.yml), so the bytes reach the service which
        // performs the size check.
        byte[] huge = new byte[6 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile(
                "file", "big.png", "image/png", huge);

        mockMvc.perform(multipart("/api/v1/me/support-tickets/attachments")
                        .file(file)
                        .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ATTACHMENT"));
    }

    @Test
    void preUpload_rejectsBadMime_400_INVALID_ATTACHMENT() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/me/support-tickets/attachments")
                        .file(file)
                        .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ATTACHMENT"));
    }

    /* ============================================================ */
    /* GET /api/v1/support-tickets/attachments/{publicId}           */
    /* ============================================================ */

    @Test
    void signedUrl_owner_returns200WithUrl() throws Exception {
        SupportTicketAttachment att = seedAttachment(user);
        when(storage.presignGet(eq(att.getStorageKey()), any()))
                .thenReturn("https://example.com/presigned-owner");

        mockMvc.perform(get("/api/v1/support-tickets/attachments/" + att.getPublicId())
                        .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://example.com/presigned-owner"));
    }

    @Test
    void signedUrl_admin_returns200() throws Exception {
        SupportTicketAttachment att = seedAttachment(user);
        when(storage.presignGet(eq(att.getStorageKey()), any()))
                .thenReturn("https://example.com/presigned-admin");

        mockMvc.perform(get("/api/v1/support-tickets/attachments/" + att.getPublicId())
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://example.com/presigned-admin"));
    }

    @Test
    void signedUrl_otherUser_returns403_NOT_OWNER() throws Exception {
        SupportTicketAttachment att = seedAttachment(user);

        mockMvc.perform(get("/api/v1/support-tickets/attachments/" + att.getPublicId())
                        .header("Authorization", "Bearer " + otherJwt))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_OWNER"));
    }

    @Test
    void signedUrl_unknown_publicId_returns404_ATTACHMENT_NOT_FOUND() throws Exception {
        mockMvc.perform(get("/api/v1/support-tickets/attachments/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_NOT_FOUND"));
    }

    /* ============================================================ */
    /* Helpers                                                      */
    /* ============================================================ */

    private SupportTicketAttachment seedAttachment(User owner) {
        OffsetDateTime now = OffsetDateTime.now();
        SupportTicket ticket = ticketRepo.save(SupportTicket.builder()
                .user(owner)
                .subject("subj-" + shortId())
                .category(SupportTicketCategory.OTHER)
                .status(SupportTicketStatus.OPEN)
                .lastMessageAt(now)
                .lastMessageAuthor(SupportTicketAuthorRole.USER)
                .build());
        SupportTicketMessage message = messageRepo.save(SupportTicketMessage.builder()
                .ticket(ticket)
                .authorUser(owner)
                .authorRole(SupportTicketAuthorRole.USER)
                .body("body-" + shortId())
                .visibleToUser(true)
                .build());
        SupportTicketAttachment att = attachmentRepo.save(SupportTicketAttachment.builder()
                .message(message)
                .storageKey("support-attachments/" + message.getId() + "/" + UUID.randomUUID() + ".png")
                .mimeType("image/png")
                .sizeBytes(1024)
                .width(800)
                .height(600)
                .build());
        em.flush();
        em.clear();
        return att;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
