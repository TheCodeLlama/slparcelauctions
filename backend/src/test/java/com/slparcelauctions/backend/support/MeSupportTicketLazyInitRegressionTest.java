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
 * <p>The companion {@link MeSupportTicketControllerIntegrationTest} runs every
 * test method inside a class-level {@code @Transactional}, which causes
 * MockMvc's invocation to share the test's open session - so the bug never
 * triggers there. This class deliberately omits {@code @Transactional} on the
 * test methods so the controller call sees the same boundary as a real HTTP
 * request: setup commits, the controller runs in its own transaction, and the
 * post-service mapper has to survive a closed session.
 *
 * <p>{@code GET /api/v1/me/support-tickets/{publicId}} dereferences
 * {@code ticket.messages}, {@code message.authorUser}, {@code message.attachments},
 * and the submitter's {@link User} - every one of these is LAZY and would
 * trip {@code LazyInitializationException} without the
 * {@code @Transactional(readOnly = true)} on the controller method.
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
class MeSupportTicketLazyInitRegressionTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepo;
    @Autowired SupportTicketRepository ticketRepo;
    @Autowired SupportTicketMessageRepository messageRepo;
    @Autowired SupportTicketAttachmentRepository attachmentRepo;

    // Mocked away so the support context wires cleanly even though we do
    // not exercise S3 in this test.
    @MockitoBean ObjectStorageService storage;

    private User user;
    private User admin;
    private String userJwt;
    private Long ticketId;
    private final List<Long> createdAttachmentIds = new ArrayList<>();
    private final List<Long> createdMessageIds = new ArrayList<>();

    @BeforeEach
    @Transactional
    void seed() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        user = userRepo.save(User.builder()
                .username("lazy-init-user-" + suffix)
                .email("lazy-init-user-" + suffix + "@example.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("LazyInit User")
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
        userJwt = jwtService.issueAccessToken(new AuthPrincipal(
                user.getId(), user.getPublicId(), user.getEmail(), 0L, Role.USER));

        OffsetDateTime now = OffsetDateTime.now();
        SupportTicket ticket = ticketRepo.save(SupportTicket.builder()
                .user(user)
                .subject("LazyInit subject " + suffix)
                .category(SupportTicketCategory.ACCOUNT)
                .status(SupportTicketStatus.OPEN)
                .lastMessageAt(now)
                .lastMessageAuthor(SupportTicketAuthorRole.USER)
                .build());
        ticketId = ticket.getId();

        // 2 messages on the ticket: one visible user message and one
        // visible admin reply. We deliberately do NOT seed an internal
        // note in this regression class so the assertion below can keep
        // it simple - the integration test covers internal-note filtering.
        // ... actually plan asks for it, see below.
        SupportTicketMessage m1 = messageRepo.save(SupportTicketMessage.builder()
                .ticket(ticket)
                .authorUser(user)
                .authorRole(SupportTicketAuthorRole.USER)
                .body("user-visible body")
                .visibleToUser(true)
                .build());
        SupportTicketMessage m2 = messageRepo.save(SupportTicketMessage.builder()
                .ticket(ticket)
                .authorUser(admin)
                .authorRole(SupportTicketAuthorRole.ADMIN)
                .body("admin internal note - should be filtered")
                .visibleToUser(false)
                .build());
        createdMessageIds.add(m1.getId());
        createdMessageIds.add(m2.getId());

        // One attachment on the visible message - exercises the
        // messages.attachments LAZY traversal.
        SupportTicketAttachment att = attachmentRepo.save(SupportTicketAttachment.builder()
                .message(m1)
                .storageKey("support-attachments/" + m1.getId() + "/lazy-init-key")
                .mimeType("image/png")
                .sizeBytes(1234)
                .width(100)
                .height(80)
                .build());
        createdAttachmentIds.add(att.getId());
    }

    @AfterEach
    @Transactional
    void cleanup() {
        // Children first, then ticket, then users.
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
        if (user != null) {
            userRepo.findById(user.getId()).ifPresent(userRepo::delete);
        }
        createdAttachmentIds.clear();
        createdMessageIds.clear();
    }

    @Test
    void getDetail_outsideEnclosingTx_initializesLazyCollections() throws Exception {
        // Without @Transactional(readOnly=true) on the controller method
        // (and the @EntityGraph on findByPublicId), the mapper trips
        // LazyInitializationException on ticket.messages /
        // messages.attachments / authorUser, the global handler returns
        // 500, and the assertions below fail with a Problem JSON body
        // instead of the expected support-ticket payload.
        UUID publicId = ticketRepo.findById(ticketId).orElseThrow().getPublicId();
        mockMvc.perform(get("/api/v1/me/support-tickets/" + publicId)
                        .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(publicId.toString()))
                // Only the visible message survives the user-facing filter.
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].body").value("user-visible body"))
                .andExpect(jsonPath("$.messages[0].visibleToUser").value(true))
                // Attachment was eagerly fetched + serialized.
                .andExpect(jsonPath("$.messages[0].attachments.length()").value(1))
                .andExpect(jsonPath("$.messages[0].attachments[0].mimeType").value("image/png"))
                // Internal note must not leak through the user surface even
                // outside an enclosing transaction.
                .andExpect(jsonPath("$.messages[?(@.visibleToUser == false)]").isEmpty());
    }

    @Test
    void getList_outsideEnclosingTx_returnsAtLeastOneRow() throws Exception {
        // List path: MeSupportTicketController.list maps each
        // Page<SupportTicket> entry via mapper.toSummaryDto, which
        // dereferences ticket.lastMessageAuthor / status / category /
        // subject. None of those are LAZY collections, but the
        // @Transactional(readOnly=true) on the controller still matters
        // because the underlying Page#map closure runs after the service
        // tx closes. This test guards against future regressions where a
        // mapper enrichment dereferences a LAZY field.
        mockMvc.perform(get("/api/v1/me/support-tickets")
                        .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }
}
