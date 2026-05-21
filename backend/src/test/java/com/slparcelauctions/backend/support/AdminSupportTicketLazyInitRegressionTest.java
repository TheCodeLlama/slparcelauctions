package com.slparcelauctions.backend.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Regression coverage for the {@link org.hibernate.LazyInitializationException}
 * class of bug that surfaced on {@code GET /api/v1/admin/coupons/{publicId}}
 * (PR #388): a controller mapper iterated a LAZY collection after the service
 * transaction closed.
 *
 * <p>The companion {@link AdminSupportTicketControllerIntegrationTest} runs
 * every test method inside a class-level {@code @Transactional}, which causes
 * MockMvc's invocation to share the test's open session - so the bug never
 * triggers there. This class deliberately omits {@code @Transactional} on the
 * test methods so the controller call sees the same boundary as a real HTTP
 * request: setup commits, the controller runs in its own transaction, and the
 * post-service mapper has to survive a closed session.
 *
 * <p>The admin detail path dereferences {@code ticket.messages},
 * {@code message.authorUser}, {@code message.attachments}, the submitter
 * {@link User}, and the assigned-admin {@link User}. Every collection is LAZY
 * and would trip {@code LazyInitializationException} without the
 * {@code @Transactional(readOnly = true)} on the controller method. The admin
 * DTO must also serialize internal notes (unlike the user DTO), so this test
 * seeds an invisible message and asserts the admin response includes it.
 *
 * <p>Cleanup is manual in {@link #cleanup()} because there's no
 * test-wrapping rollback.
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
class AdminSupportTicketLazyInitRegressionTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepo;
    @Autowired SupportTicketRepository ticketRepo;
    @Autowired SupportTicketMessageRepository messageRepo;
    @Autowired SupportTicketAttachmentRepository attachmentRepo;

    // Mocked away so the support context wires cleanly even though we do
    // not exercise S3 in this test.
    @MockitoBean ObjectStorageService storage;

    private User submitter;
    private User admin;
    private String adminJwt;
    private Long ticketId;
    private final List<Long> createdAttachmentIds = new ArrayList<>();
    private final List<Long> createdMessageIds = new ArrayList<>();

    @BeforeEach
    @Transactional
    void seed() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        submitter = userRepo.save(User.builder()
                .username("lazy-init-submitter-" + suffix)
                .email("lazy-init-submitter-" + suffix + "@example.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("LazyInit Submitter")
                .role(Role.USER)
                .build());
        admin = userRepo.save(User.builder()
                .username("lazy-init-admin-" + suffix)
                .email("lazy-init-admin-" + suffix + "@example.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("LazyInit Admin")
                .role(Role.ADMIN)
                .build());
        adminJwt = jwtService.issueAccessToken(new AuthPrincipal(
                admin.getId(), admin.getPublicId(), admin.getEmail(), 0L, Role.ADMIN));

        OffsetDateTime now = OffsetDateTime.now();
        SupportTicket ticket = ticketRepo.save(SupportTicket.builder()
                .user(submitter)
                .subject("LazyInit admin subject " + suffix)
                .category(SupportTicketCategory.ACCOUNT)
                .status(SupportTicketStatus.OPEN)
                .lastMessageAt(now)
                .lastMessageAuthor(SupportTicketAuthorRole.USER)
                .assignedAdminId(admin.getId())
                .build());
        ticketId = ticket.getId();

        // Visible user message + invisible admin internal note + visible
        // admin reply. The admin endpoint must serialize all three including
        // the internal note (unlike the user endpoint, which filters it).
        SupportTicketMessage m1 = messageRepo.save(SupportTicketMessage.builder()
                .ticket(ticket)
                .authorUser(submitter)
                .authorRole(SupportTicketAuthorRole.USER)
                .body("user-visible body")
                .visibleToUser(true)
                .build());
        SupportTicketMessage m2 = messageRepo.save(SupportTicketMessage.builder()
                .ticket(ticket)
                .authorUser(admin)
                .authorRole(SupportTicketAuthorRole.ADMIN)
                .body("internal admin note")
                .visibleToUser(false)
                .build());
        SupportTicketMessage m3 = messageRepo.save(SupportTicketMessage.builder()
                .ticket(ticket)
                .authorUser(admin)
                .authorRole(SupportTicketAuthorRole.ADMIN)
                .body("public admin reply")
                .visibleToUser(true)
                .build());
        createdMessageIds.add(m1.getId());
        createdMessageIds.add(m2.getId());
        createdMessageIds.add(m3.getId());

        // One attachment on the visible user message - exercises the
        // messages.attachments LAZY traversal.
        SupportTicketAttachment att = attachmentRepo.save(SupportTicketAttachment.builder()
                .message(m1)
                .storageKey("support-attachments/" + m1.getId() + "/lazy-init-admin-key")
                .mimeType("image/png")
                .sizeBytes(2048)
                .width(120)
                .height(90)
                .build());
        createdAttachmentIds.add(att.getId());
    }

    @AfterEach
    @Transactional
    void cleanup() {
        for (Long aid : createdAttachmentIds) {
            attachmentRepo.findById(aid).ifPresent(attachmentRepo::delete);
        }
        for (Long mid : createdMessageIds) {
            messageRepo.findById(mid).ifPresent(messageRepo::delete);
        }
        if (ticketId != null) {
            ticketRepo.findById(ticketId).ifPresent(ticketRepo::delete);
        }
        if (admin != null) {
            userRepo.findById(admin.getId()).ifPresent(userRepo::delete);
        }
        if (submitter != null) {
            userRepo.findById(submitter.getId()).ifPresent(userRepo::delete);
        }
        createdAttachmentIds.clear();
        createdMessageIds.clear();
    }

    @Test
    void getDetail_outsideEnclosingTx_initializesLazyCollections() throws Exception {
        // Without @Transactional(readOnly=true) on the controller method,
        // the mapper trips LazyInitializationException on ticket.messages /
        // messages.attachments / authorUser / assignedAdmin, the global
        // handler returns 500, and the assertions below fail with a Problem
        // JSON body instead of the expected support-ticket payload.
        UUID publicId = ticketRepo.findById(ticketId).orElseThrow().getPublicId();
        mockMvc.perform(get("/api/v1/admin/support-tickets/" + publicId)
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(publicId.toString()))
                // Admin DTO includes internal notes - 3 messages total.
                .andExpect(jsonPath("$.messages.length()").value(3))
                .andExpect(jsonPath("$.messages[?(@.visibleToUser == false)].body")
                        .value(org.hamcrest.Matchers.hasItem("internal admin note")))
                .andExpect(jsonPath("$.messages[?(@.visibleToUser == true)].body")
                        .value(org.hamcrest.Matchers.hasItem("user-visible body")))
                // Attachment was eagerly fetched + serialized.
                .andExpect(jsonPath("$.messages[?(@.body == 'user-visible body')].attachments[0].mimeType")
                        .value(org.hamcrest.Matchers.hasItem("image/png")))
                // Assigned-admin enrichment also traversed without LazyInit.
                .andExpect(jsonPath("$.assignedAdminPublicId").value(admin.getPublicId().toString()));
    }

    @Test
    void getQueue_outsideEnclosingTx_returnsAtLeastOneRow() throws Exception {
        // Queue path: AdminSupportTicketController.queue maps each
        // Page<SupportTicket> entry via mapper.toAdminRow plus an
        // assigned-admin User lookup. The @Transactional(readOnly=true) on
        // the controller method guards future regressions where a mapper
        // enrichment dereferences a LAZY field.
        mockMvc.perform(get("/api/v1/admin/support-tickets")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }
}
