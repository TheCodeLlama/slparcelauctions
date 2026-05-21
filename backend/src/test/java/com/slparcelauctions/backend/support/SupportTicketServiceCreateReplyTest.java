package com.slparcelauctions.backend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slparcelauctions.backend.media.ImageFormat;
import com.slparcelauctions.backend.media.ImageUploadValidator;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.storage.ObjectStorageService;
import com.slparcelauctions.backend.support.dto.AdminReplyRequest;
import com.slparcelauctions.backend.support.dto.CreateSupportTicketRequest;
import com.slparcelauctions.backend.support.dto.ReplySupportTicketRequest;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Plan Task 8 -- integration coverage for the create + reply core of
 * {@link SupportTicketService}.
 *
 * <p>Real DB + real {@link SupportTicketAttachmentService} so attachment
 * promotion writes a real row, with {@link ObjectStorageService} and
 * {@link ImageUploadValidator} mocked away (no S3, no image decoding).
 * {@link NotificationPublisher} is a {@link MockitoBean} so we can verify
 * which fan-out method fires and with which arguments.
 *
 * <p>Mirrors the {@code @SpringBootTest + @ActiveProfiles("dev") + scheduler-mute}
 * pattern used by the rest of the support test suite, plus
 * {@code @Transactional} so each test rolls back.
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
class SupportTicketServiceCreateReplyTest {

    @Autowired SupportTicketService service;
    @Autowired SupportTicketRepository ticketRepo;
    @Autowired SupportTicketAttachmentRepository attachmentRepo;
    @Autowired UserRepository userRepo;
    @Autowired StringRedisTemplate redis;

    @MockitoBean NotificationPublisher notifications;
    @MockitoBean ObjectStorageService storage;
    @MockitoBean ImageUploadValidator imageValidator;

    @PersistenceContext EntityManager em;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> redisKeysToClean = new ArrayList<>();

    private User submitter;
    private User admin1;
    private User admin2;

    @BeforeEach
    void seed() {
        submitter = userRepo.save(User.builder()
                .username("submitter-" + shortId())
                .email("submitter-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Submitter")
                .role(Role.USER)
                .verified(true)
                .build());
        admin1 = userRepo.save(User.builder()
                .username("admin-one-" + shortId())
                .email("admin-one-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Admin One")
                .role(Role.ADMIN)
                .verified(true)
                .build());
        admin2 = userRepo.save(User.builder()
                .username("admin-two-" + shortId())
                .email("admin-two-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Admin Two")
                .role(Role.ADMIN)
                .verified(true)
                .build());
        em.flush();

        // Default validator stub: 100x100 PNG. Individual tests can override.
        when(imageValidator.validate(any(byte[].class), anyLong(), anyInt()))
                .thenReturn(new ImageUploadValidator.ValidationResult(
                        ImageFormat.PNG,
                        new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB),
                        100, 100));
    }

    @AfterEach
    void cleanupRedis() {
        for (String k : redisKeysToClean) {
            redis.delete(k);
        }
        redisKeysToClean.clear();
    }

    // ── createTicket ──────────────────────────────────────────────────────────

    @Test
    void createTicket_inserts_row_and_initial_message_and_notifies_admins() {
        CreateSupportTicketRequest req = new CreateSupportTicketRequest(
                "Wallet stuck", SupportTicketCategory.WALLET,
                "I deposited L$500 and the balance never updated.", null);

        SupportTicket created = service.createTicket(submitter.getId(), req);
        em.flush();
        em.clear();

        SupportTicket loaded = ticketRepo.findByPublicId(created.getPublicId()).orElseThrow();
        assertThat(loaded.getSubject()).isEqualTo("Wallet stuck");
        assertThat(loaded.getCategory()).isEqualTo(SupportTicketCategory.WALLET);
        assertThat(loaded.getStatus()).isEqualTo(SupportTicketStatus.OPEN);
        assertThat(loaded.getLastMessageAuthor()).isEqualTo(SupportTicketAuthorRole.USER);
        assertThat(loaded.getUser().getId()).isEqualTo(submitter.getId());

        Set<SupportTicketMessage> messages = loaded.getMessages();
        assertThat(messages).hasSize(1);
        SupportTicketMessage initial = messages.iterator().next();
        assertThat(initial.getBody()).isEqualTo("I deposited L$500 and the balance never updated.");
        assertThat(initial.getAuthorRole()).isEqualTo(SupportTicketAuthorRole.USER);
        assertThat(initial.getVisibleToUser()).isTrue();
        assertThat(initial.getAuthorUser().getId()).isEqualTo(submitter.getId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> adminIdsCap = ArgumentCaptor.forClass(List.class);
        verify(notifications).supportTicketOpened(
                adminIdsCap.capture(), eq(created.getPublicId()),
                eq("Wallet stuck"), eq("Submitter"), eq("WALLET"));
        // DB-scoped test: the dev DB may carry admins seeded by other test
        // suites we don't roll back. Assert containment of our seeded admins,
        // not strict equality.
        assertThat(adminIdsCap.getValue()).contains(admin1.getId(), admin2.getId());
    }

    @Test
    void createTicket_propagates_rate_limit_at_cap() {
        for (int i = 0; i < 5; i++) {
            service.createTicket(submitter.getId(), new CreateSupportTicketRequest(
                    "subj " + i, SupportTicketCategory.OTHER, "body " + i, null));
        }
        em.flush();

        assertThatThrownBy(() -> service.createTicket(submitter.getId(),
                new CreateSupportTicketRequest("over", SupportTicketCategory.OTHER, "body", null)))
                .isInstanceOfSatisfying(SupportTicketException.class,
                        e -> assertThat(e.getCode())
                                .isEqualTo(SupportTicketError.RATE_LIMITED));
    }

    @Test
    void createTicket_persists_attachments_when_provided() throws Exception {
        String key1 = seedPendingAttachment(submitter.getId(),
                "support-attachments/pending/u/one.png", "image/png", 1024, 100, 100);
        String key2 = seedPendingAttachment(submitter.getId(),
                "support-attachments/pending/u/two.png", "image/png", 2048, 200, 200);

        SupportTicket created = service.createTicket(submitter.getId(),
                new CreateSupportTicketRequest(
                        "with attachments", SupportTicketCategory.LISTING,
                        "see screenshots", List.of(key1, key2)));
        em.flush();
        em.clear();

        SupportTicket loaded = ticketRepo.findByPublicId(created.getPublicId()).orElseThrow();
        SupportTicketMessage initial = loaded.getMessages().iterator().next();
        assertThat(initial.getAttachments()).hasSize(2);
        verify(storage, times(2)).copy(any(), any());
    }

    // ── userReply ─────────────────────────────────────────────────────────────

    @Test
    void userReply_throws_UNKNOWN_TICKET_when_caller_is_not_submitter() {
        SupportTicket ticket = service.createTicket(submitter.getId(), new CreateSupportTicketRequest(
                "subj", SupportTicketCategory.OTHER, "body", null));
        em.flush();

        long otherUserId = admin1.getId(); // any non-submitter id
        assertThatThrownBy(() -> service.userReply(otherUserId, ticket.getPublicId(),
                new ReplySupportTicketRequest("nope", null)))
                .isInstanceOfSatisfying(SupportTicketException.class,
                        e -> assertThat(e.getCode())
                                .isEqualTo(SupportTicketError.UNKNOWN_TICKET));
    }

    @Test
    void userReply_visible_message_updates_lastMessageAt_and_lastMessageAuthor() throws Exception {
        SupportTicket ticket = service.createTicket(submitter.getId(), new CreateSupportTicketRequest(
                "subj", SupportTicketCategory.OTHER, "first", null));
        em.flush();
        OffsetDateTime before = ticket.getLastMessageAt();
        // Force a tick so the new lastMessageAt is strictly after the prior one.
        Thread.sleep(5);

        SupportTicketMessage reply = service.userReply(submitter.getId(), ticket.getPublicId(),
                new ReplySupportTicketRequest("follow up", null));
        em.flush();
        em.clear();

        SupportTicket reloaded = ticketRepo.findByPublicId(ticket.getPublicId()).orElseThrow();
        assertThat(reloaded.getLastMessageAuthor()).isEqualTo(SupportTicketAuthorRole.USER);
        assertThat(reloaded.getLastMessageAt()).isAfter(before);
        assertThat(reloaded.getMessages()).hasSize(2);
        assertThat(reply.getBody()).isEqualTo("follow up");
    }

    @Test
    void userReply_on_resolved_ticket_auto_reopens() {
        SupportTicket ticket = service.createTicket(submitter.getId(), new CreateSupportTicketRequest(
                "subj", SupportTicketCategory.OTHER, "body", null));
        em.flush();
        // Simulate the admin-resolve path (Task 9 lands the real mutator;
        // for this test we hand-flip the persisted state).
        ticket.setStatus(SupportTicketStatus.RESOLVED);
        ticket.setResolvedAt(OffsetDateTime.now());
        ticketRepo.save(ticket);
        em.flush();

        service.userReply(submitter.getId(), ticket.getPublicId(),
                new ReplySupportTicketRequest("still broken", null));
        em.flush();
        em.clear();

        SupportTicket reloaded = ticketRepo.findByPublicId(ticket.getPublicId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SupportTicketStatus.OPEN);
        assertThat(reloaded.getResolvedAt()).isNull();
    }

    @Test
    void userReply_fires_supportTicketUserReplied_to_all_admins() {
        SupportTicket ticket = service.createTicket(submitter.getId(), new CreateSupportTicketRequest(
                "subj", SupportTicketCategory.OTHER, "body", null));
        em.flush();
        // Reset captured invocations from the createTicket above.
        org.mockito.Mockito.clearInvocations(notifications);

        service.userReply(submitter.getId(), ticket.getPublicId(),
                new ReplySupportTicketRequest("ping", null));
        em.flush();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> adminIdsCap = ArgumentCaptor.forClass(List.class);
        verify(notifications).supportTicketUserReplied(
                adminIdsCap.capture(), eq(ticket.getPublicId()),
                eq("subj"), eq("Submitter"));
        // See createTicket happy-path test for the rationale on `contains` vs
        // strict equality - DB carries admins seeded by other test suites.
        assertThat(adminIdsCap.getValue()).contains(admin1.getId(), admin2.getId());
        verify(notifications, never()).supportTicketAdminReplied(
                anyLong(), any(UUID.class), any(), any());
    }

    // ── adminReply ────────────────────────────────────────────────────────────

    @Test
    void adminReply_visible_fires_supportTicketAdminReplied_to_submitter() {
        SupportTicket ticket = service.createTicket(submitter.getId(), new CreateSupportTicketRequest(
                "subj", SupportTicketCategory.OTHER, "body", null));
        em.flush();
        org.mockito.Mockito.clearInvocations(notifications);

        service.adminReply(admin1.getId(), ticket.getPublicId(),
                new AdminReplyRequest("checking on this", null, false));
        em.flush();
        em.clear();

        verify(notifications).supportTicketAdminReplied(
                eq(submitter.getId()), eq(ticket.getPublicId()),
                eq("subj"), eq("Admin One"));

        SupportTicket reloaded = ticketRepo.findByPublicId(ticket.getPublicId()).orElseThrow();
        assertThat(reloaded.getLastMessageAuthor()).isEqualTo(SupportTicketAuthorRole.ADMIN);
        assertThat(reloaded.getMessages()).hasSize(2);
        assertThat(reloaded.getMessages()).allMatch(m -> Boolean.TRUE.equals(m.getVisibleToUser()));
    }

    @Test
    void adminReply_internalNote_does_not_update_lastMessageAt_or_fire_notification() {
        SupportTicket ticket = service.createTicket(submitter.getId(), new CreateSupportTicketRequest(
                "subj", SupportTicketCategory.OTHER, "body", null));
        em.flush();
        em.clear();
        // Read the baseline back from the DB - Postgres timestamptz truncates
        // nanos to micros and normalizes the zone to UTC, so an in-memory
        // OffsetDateTime baseline will fail an isEqualTo against the reloaded
        // value even though both represent the same instant.
        OffsetDateTime baselineLastMsg = ticketRepo.findByPublicId(ticket.getPublicId())
                .orElseThrow().getLastMessageAt();
        SupportTicketAuthorRole baselineAuthor = SupportTicketAuthorRole.USER;
        org.mockito.Mockito.clearInvocations(notifications);

        service.adminReply(admin1.getId(), ticket.getPublicId(),
                new AdminReplyRequest("internal triage note", null, true));
        em.flush();
        em.clear();

        SupportTicket reloaded = ticketRepo.findByPublicId(ticket.getPublicId()).orElseThrow();
        // lastMessageAt + lastMessageAuthor unchanged from before the internal note
        assertThat(reloaded.getLastMessageAt()).isEqualTo(baselineLastMsg);
        assertThat(reloaded.getLastMessageAuthor()).isEqualTo(baselineAuthor);
        // Internal note was persisted as a non-user-visible row
        assertThat(reloaded.getMessages()).hasSize(2);
        assertThat(reloaded.getMessages())
                .anyMatch(m -> Boolean.FALSE.equals(m.getVisibleToUser())
                        && m.getBody().equals("internal triage note"));

        // No notifications fired for the internal note
        verify(notifications, never()).supportTicketAdminReplied(
                anyLong(), any(UUID.class), any(), any());
        verify(notifications, never()).supportTicketUserReplied(
                any(), any(UUID.class), any(), any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

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
