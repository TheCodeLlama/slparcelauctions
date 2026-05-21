package com.slparcelauctions.backend.support;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.support.dto.AdminReplyRequest;
import com.slparcelauctions.backend.support.dto.CreateSupportTicketRequest;
import com.slparcelauctions.backend.support.dto.ReplySupportTicketRequest;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Core write-path service for customer support tickets. Owns
 * {@code createTicket}, {@code userReply}, and {@code adminReply} plus the
 * private {@code appendMessage} helper that every reply path funnels through.
 *
 * <p>Auto-reopen is intentionally scoped: a user replying to a
 * {@link SupportTicketStatus#RESOLVED} ticket flips it back to
 * {@link SupportTicketStatus#OPEN}; an admin replying to a resolved ticket
 * does NOT reopen it (the admin can resolve again or use an explicit
 * resolve / reopen action from Task 9). Internal notes
 * ({@code visibleToUser = false}) are silent end-to-end - they do not bump
 * {@code lastMessageAt}, do not mutate {@code lastMessageAuthor}, and fire
 * no notifications. The notification fan-out for admin recipients reads from
 * {@link UserRepository#findIdsByRole(Role)} to avoid hydrating full
 * {@link User} entities into the persistence context just for an id list.
 *
 * <p>Ownership checks return {@link SupportTicketError#UNKNOWN_TICKET} rather
 * than {@code NOT_OWNER} on a wrong-user-id mismatch so an attacker probing
 * for ticket existence cannot distinguish "this ticket does not exist" from
 * "this ticket exists but belongs to someone else".
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-05-21-customer-support-contact-design.md}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class SupportTicketService {

    private final SupportTicketRepository ticketRepo;
    private final SupportTicketMessageRepository messageRepo;
    private final SupportTicketAttachmentRepository attachmentRepo;
    private final SupportTicketAttachmentService attachmentService;
    private final SupportTicketRateLimiter rateLimiter;
    private final NotificationPublisher notifications;
    private final UserRepository userRepo;

    public SupportTicket createTicket(long submitterUserId, CreateSupportTicketRequest req) {
        rateLimiter.assertCanOpenNewTicket(submitterUserId);
        User submitter = userRepo.findById(submitterUserId).orElseThrow(() ->
                new SupportTicketException(SupportTicketError.UNKNOWN_TICKET, "submitter missing"));

        OffsetDateTime now = OffsetDateTime.now();
        SupportTicket ticket = SupportTicket.builder()
                .user(submitter)
                .subject(req.subject().trim())
                .category(req.category())
                .status(SupportTicketStatus.OPEN)
                .lastMessageAt(now)
                .lastMessageAuthor(SupportTicketAuthorRole.USER)
                .build();
        ticket = ticketRepo.save(ticket);

        SupportTicketMessage initial = SupportTicketMessage.builder()
                .ticket(ticket)
                .authorUser(submitter)
                .authorRole(SupportTicketAuthorRole.USER)
                .body(req.body().trim())
                .visibleToUser(true)
                .build();
        initial = messageRepo.save(initial);

        if (req.attachmentKeys() != null && !req.attachmentKeys().isEmpty()) {
            attachmentService.promote(req.attachmentKeys(), submitter.getId(), initial, attachmentRepo);
        }

        List<Long> adminIds = userRepo.findIdsByRole(Role.ADMIN);
        notifications.supportTicketOpened(
                adminIds, ticket.getPublicId(), ticket.getSubject(),
                displayNameOf(submitter), ticket.getCategory().name());

        return ticket;
    }

    public SupportTicketMessage userReply(long callerUserId, UUID ticketPublicId,
                                          ReplySupportTicketRequest req) {
        SupportTicket ticket = ticketRepo.findByPublicId(ticketPublicId)
                .orElseThrow(() -> new SupportTicketException(SupportTicketError.UNKNOWN_TICKET));
        if (!ticket.getUser().getId().equals(callerUserId)) {
            // Treat ownership mismatch as 404 so existence isn't leaked.
            throw new SupportTicketException(SupportTicketError.UNKNOWN_TICKET);
        }
        User caller = ticket.getUser();
        return appendMessage(ticket, caller, SupportTicketAuthorRole.USER,
                req.body(), req.attachmentKeys(), true);
    }

    public SupportTicketMessage adminReply(long adminUserId, UUID ticketPublicId,
                                           AdminReplyRequest req) {
        SupportTicket ticket = ticketRepo.findByPublicId(ticketPublicId)
                .orElseThrow(() -> new SupportTicketException(SupportTicketError.UNKNOWN_TICKET));
        User admin = userRepo.findById(adminUserId).orElseThrow(() ->
                new SupportTicketException(SupportTicketError.UNKNOWN_TICKET, "admin missing"));
        boolean internal = Boolean.TRUE.equals(req.internalNote());
        return appendMessage(ticket, admin, SupportTicketAuthorRole.ADMIN,
                req.body(), req.attachmentKeys(), !internal);
    }

    /**
     * User-side lookup that fails as {@code UNKNOWN_TICKET} both when the
     * ticket is missing and when it exists but belongs to a different user.
     * Read-only - intended for the user-facing controller's GET-detail path
     * (Task 11).
     */
    @Transactional(readOnly = true)
    public SupportTicket findByPublicIdEnsureOwner(long userId, UUID publicId) {
        SupportTicket t = ticketRepo.findByPublicId(publicId)
                .orElseThrow(() -> new SupportTicketException(SupportTicketError.UNKNOWN_TICKET));
        if (!t.getUser().getId().equals(userId)) {
            throw new SupportTicketException(SupportTicketError.UNKNOWN_TICKET);
        }
        return t;
    }

    /**
     * Admin-side lookup with no ownership check - the admin controller
     * (Task 12) is already gated by the {@code ADMIN} role at the auth
     * filter, so an additional in-service check would be redundant.
     */
    @Transactional(readOnly = true)
    public SupportTicket findByPublicId(UUID publicId) {
        return ticketRepo.findByPublicId(publicId)
                .orElseThrow(() -> new SupportTicketException(SupportTicketError.UNKNOWN_TICKET));
    }

    private SupportTicketMessage appendMessage(SupportTicket ticket, User author,
                                               SupportTicketAuthorRole role,
                                               String body, List<String> attachmentKeys,
                                               boolean visibleToUser) {
        OffsetDateTime now = OffsetDateTime.now();
        SupportTicketMessage msg = SupportTicketMessage.builder()
                .ticket(ticket)
                .authorUser(author)
                .authorRole(role)
                .body(body.trim())
                .visibleToUser(visibleToUser)
                .build();
        msg = messageRepo.save(msg);

        if (attachmentKeys != null && !attachmentKeys.isEmpty()) {
            attachmentService.promote(attachmentKeys, author.getId(), msg, attachmentRepo);
        }

        if (visibleToUser) {
            ticket.setLastMessageAt(now);
            ticket.setLastMessageAuthor(role);
            // Auto-reopen ONLY on user reply to a resolved ticket.
            if (role == SupportTicketAuthorRole.USER
                    && ticket.getStatus() == SupportTicketStatus.RESOLVED) {
                ticket.setStatus(SupportTicketStatus.OPEN);
                ticket.setResolvedAt(null);
            }

            if (role == SupportTicketAuthorRole.ADMIN) {
                User submitter = ticket.getUser();
                notifications.supportTicketAdminReplied(
                        submitter.getId(), ticket.getPublicId(), ticket.getSubject(),
                        displayNameOf(author));
            } else {
                List<Long> adminIds = userRepo.findIdsByRole(Role.ADMIN);
                notifications.supportTicketUserReplied(
                        adminIds, ticket.getPublicId(), ticket.getSubject(),
                        displayNameOf(author));
            }
        }
        // Internal notes (visibleToUser=false): silent. Do NOT bump
        // lastMessageAt/lastMessageAuthor and fire no notifications.
        return msg;
    }

    private static String displayNameOf(User u) {
        String name = u.getDisplayName();
        return (name == null || name.isBlank()) ? u.getUsername() : name;
    }
}
