package com.slparcelauctions.backend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.support.dto.CreateSupportTicketRequest;
import com.slparcelauctions.backend.support.dto.SupportTicketQueueStatsDto;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Plan Task 9 -- integration coverage for the admin action surface of
 * {@link SupportTicketService}: {@code resolve}, {@code reopen},
 * {@code assign}, {@code patchCategory}, {@code queueStats}.
 *
 * <p>Mirrors the {@code @SpringBootTest + @ActiveProfiles("dev")} +
 * scheduler-mute pattern used by
 * {@link SupportTicketServiceCreateReplyTest}, with {@code @Transactional}
 * rollback per test and a {@link MockitoBean} {@link NotificationPublisher}
 * so we can assert which fan-out method fires.
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
class SupportTicketServiceAdminActionsTest {

    @Autowired SupportTicketService service;
    @Autowired SupportTicketRepository ticketRepo;
    @Autowired UserRepository userRepo;

    @MockitoBean NotificationPublisher notifications;

    @PersistenceContext EntityManager em;

    private User submitter;
    private User admin1;
    private User otherUser;

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
        otherUser = userRepo.save(User.builder()
                .username("user-other-" + shortId())
                .email("user-other-" + UUID.randomUUID() + "@ex.com")
                .passwordHash("x")
                .slAvatarUuid(UUID.randomUUID())
                .displayName("Other User")
                .role(Role.USER)
                .verified(true)
                .build());
        em.flush();
    }

    // -- resolve --------------------------------------------------------------

    @Test
    void resolve_idempotent_when_already_resolved() {
        SupportTicket ticket = seedTicket("subj");
        service.resolve(admin1.getId(), ticket.getPublicId());
        em.flush();
        org.mockito.Mockito.clearInvocations(notifications);

        SupportTicket second = service.resolve(admin1.getId(), ticket.getPublicId());
        em.flush();
        em.clear();

        assertThat(second.getStatus()).isEqualTo(SupportTicketStatus.RESOLVED);
        // Second call must not fire another notification or write a duplicate
        // system message.
        verify(notifications, never()).supportTicketResolved(
                anyLong(), any(UUID.class), any());
        SupportTicket reloaded = ticketRepo.findByPublicId(ticket.getPublicId()).orElseThrow();
        // Original create message + one "Marked resolved by admin" message; no third.
        assertThat(reloaded.getMessages()).hasSize(2);
    }

    @Test
    void resolve_writes_system_message_and_fires_notification() {
        SupportTicket ticket = seedTicket("wallet stuck");
        org.mockito.Mockito.clearInvocations(notifications);

        service.resolve(admin1.getId(), ticket.getPublicId());
        em.flush();
        em.clear();

        SupportTicket reloaded = ticketRepo.findByPublicId(ticket.getPublicId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SupportTicketStatus.RESOLVED);
        assertThat(reloaded.getResolvedAt()).isNotNull();
        assertThat(reloaded.getLastMessageAuthor()).isEqualTo(SupportTicketAuthorRole.ADMIN);
        assertThat(reloaded.getMessages())
                .anyMatch(m -> "Marked resolved by admin".equals(m.getBody())
                        && Boolean.TRUE.equals(m.getVisibleToUser())
                        && m.getAuthorRole() == SupportTicketAuthorRole.ADMIN);

        verify(notifications, times(1)).supportTicketResolved(
                eq(submitter.getId()), eq(ticket.getPublicId()), eq("wallet stuck"));
    }

    // -- reopen ---------------------------------------------------------------

    @Test
    void reopen_idempotent_when_already_open() {
        SupportTicket ticket = seedTicket("subj");
        // ticket is OPEN by default
        org.mockito.Mockito.clearInvocations(notifications);

        SupportTicket result = service.reopen(admin1.getId(), ticket.getPublicId());
        em.flush();
        em.clear();

        assertThat(result.getStatus()).isEqualTo(SupportTicketStatus.OPEN);
        // No system message added for a no-op reopen.
        SupportTicket reloaded = ticketRepo.findByPublicId(ticket.getPublicId()).orElseThrow();
        assertThat(reloaded.getMessages()).hasSize(1);
        verify(notifications, never()).supportTicketResolved(
                anyLong(), any(UUID.class), any());
    }

    @Test
    void reopen_writes_system_message_and_no_notification() {
        SupportTicket ticket = seedTicket("subj");
        service.resolve(admin1.getId(), ticket.getPublicId());
        em.flush();
        org.mockito.Mockito.clearInvocations(notifications);

        service.reopen(admin1.getId(), ticket.getPublicId());
        em.flush();
        em.clear();

        SupportTicket reloaded = ticketRepo.findByPublicId(ticket.getPublicId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SupportTicketStatus.OPEN);
        assertThat(reloaded.getResolvedAt()).isNull();
        assertThat(reloaded.getLastMessageAuthor()).isEqualTo(SupportTicketAuthorRole.ADMIN);
        assertThat(reloaded.getMessages())
                .anyMatch(m -> "Reopened by admin".equals(m.getBody())
                        && Boolean.TRUE.equals(m.getVisibleToUser())
                        && m.getAuthorRole() == SupportTicketAuthorRole.ADMIN);

        // No notifications on reopen.
        verify(notifications, never()).supportTicketResolved(
                anyLong(), any(UUID.class), any());
        verify(notifications, never()).supportTicketAdminReplied(
                anyLong(), any(UUID.class), any(), any());
        verify(notifications, never()).supportTicketUserReplied(
                any(), any(UUID.class), any(), any());
        verify(notifications, never()).supportTicketOpened(
                any(), any(UUID.class), any(), any(), any());
    }

    // -- assign ---------------------------------------------------------------

    @Test
    void assign_with_valid_admin_publicId_sets_assignedAdminId() {
        SupportTicket ticket = seedTicket("subj");
        em.flush();

        service.assign(ticket.getPublicId(), admin1.getPublicId());
        em.flush();
        em.clear();

        SupportTicket reloaded = ticketRepo.findByPublicId(ticket.getPublicId()).orElseThrow();
        assertThat(reloaded.getAssignedAdminId()).isEqualTo(admin1.getId());
    }

    @Test
    void assign_with_null_unassigns() {
        SupportTicket ticket = seedTicket("subj");
        service.assign(ticket.getPublicId(), admin1.getPublicId());
        em.flush();

        service.assign(ticket.getPublicId(), null);
        em.flush();
        em.clear();

        SupportTicket reloaded = ticketRepo.findByPublicId(ticket.getPublicId()).orElseThrow();
        assertThat(reloaded.getAssignedAdminId()).isNull();
    }

    @Test
    void assign_rejects_non_admin_user() {
        SupportTicket ticket = seedTicket("subj");
        em.flush();

        assertThatThrownBy(() -> service.assign(ticket.getPublicId(), otherUser.getPublicId()))
                .isInstanceOfSatisfying(SupportTicketException.class,
                        e -> assertThat(e.getCode())
                                .isEqualTo(SupportTicketError.UNKNOWN_TICKET));
    }

    // -- patchCategory --------------------------------------------------------

    @Test
    void patchCategory_updates_value() {
        SupportTicket ticket = seedTicket("subj");
        // Default category from seedTicket() is OTHER; flip to WALLET.
        em.flush();

        service.patchCategory(ticket.getPublicId(), SupportTicketCategory.WALLET);
        em.flush();
        em.clear();

        SupportTicket reloaded = ticketRepo.findByPublicId(ticket.getPublicId()).orElseThrow();
        assertThat(reloaded.getCategory()).isEqualTo(SupportTicketCategory.WALLET);
    }

    // -- queueStats -----------------------------------------------------------

    @Test
    void queueStats_counts_open_total_and_needing_admin_reply() {
        // Capture a baseline because the dev DB is shared across test classes
        // and may carry rows we don't roll back (admins seeded by other suites
        // etc). We assert relative deltas against that baseline.
        SupportTicketQueueStatsDto baseline = service.queueStats();

        // Seed 2 OPEN tickets where last_message_author = USER (default after
        // createTicket).
        SupportTicket t1 = seedTicket("needs reply 1");
        SupportTicket t2 = seedTicket("needs reply 2");
        // Seed 1 OPEN ticket where last_message_author = ADMIN (admin replied
        // last - so does NOT count in openNeedingAdminReply).
        SupportTicket t3 = seedTicket("admin replied last");
        t3.setLastMessageAuthor(SupportTicketAuthorRole.ADMIN);
        ticketRepo.save(t3);
        // Seed 1 RESOLVED ticket (does NOT count in either bucket).
        SupportTicket t4 = seedTicket("resolved one");
        service.resolve(admin1.getId(), t4.getPublicId());

        em.flush();

        SupportTicketQueueStatsDto stats = service.queueStats();

        // openTotal grew by 3 (t1, t2, t3); t4 is resolved.
        assertThat(stats.openTotal()).isEqualTo(baseline.openTotal() + 3);
        // openNeedingAdminReply grew by 2 (t1, t2 are USER-last-author);
        // t3 is ADMIN-last-author so it's excluded.
        assertThat(stats.openNeedingAdminReply())
                .isEqualTo(baseline.openNeedingAdminReply() + 2);
    }

    // -- helpers --------------------------------------------------------------

    private SupportTicket seedTicket(String subject) {
        return service.createTicket(submitter.getId(), new CreateSupportTicketRequest(
                subject, SupportTicketCategory.OTHER, "body for " + subject, null));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
