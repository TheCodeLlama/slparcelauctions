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
import com.slparcelauctions.backend.support.dto.SupportTicketQueueStatsDto;
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

    /**
     * Flip a ticket to {@link SupportTicketStatus#RESOLVED}, writing a
     * synthetic admin-authored system message ("Marked resolved by admin")
     * so the resolution shows in the thread, and firing the
     * {@code supportTicketResolved} notification to the submitter.
     * Idempotent: a no-op when already resolved.
     */
    public SupportTicket resolve(long adminUserId, UUID ticketPublicId) {
        SupportTicket ticket = ticketRepo.findByPublicId(ticketPublicId)
                .orElseThrow(() -> new SupportTicketException(SupportTicketError.UNKNOWN_TICKET));
        if (ticket.getStatus() == SupportTicketStatus.RESOLVED) return ticket;

        OffsetDateTime now = OffsetDateTime.now();
        User admin = userRepo.findById(adminUserId).orElseThrow(() ->
                new SupportTicketException(SupportTicketError.UNKNOWN_TICKET, "admin missing"));
        ticket.setStatus(SupportTicketStatus.RESOLVED);
        ticket.setResolvedAt(now);

        SupportTicketMessage system = SupportTicketMessage.builder()
                .ticket(ticket).authorUser(admin)
                .authorRole(SupportTicketAuthorRole.ADMIN)
                .body("Marked resolved by admin")
                .visibleToUser(true)
                .build();
        messageRepo.save(system);
        ticket.setLastMessageAt(now);
        ticket.setLastMessageAuthor(SupportTicketAuthorRole.ADMIN);

        User submitter = ticket.getUser();
        notifications.supportTicketResolved(submitter.getId(), ticket.getPublicId(), ticket.getSubject());
        return ticket;
    }

    /**
     * Flip a ticket back to {@link SupportTicketStatus#OPEN}, writing a
     * synthetic admin-authored system message ("Reopened by admin") so the
     * reopen shows in the thread. No notification is fired (the submitter
     * does not get spammed when an admin reopens for internal follow-up).
     * Idempotent: a no-op when already open.
     */
    public SupportTicket reopen(long adminUserId, UUID ticketPublicId) {
        SupportTicket ticket = ticketRepo.findByPublicId(ticketPublicId)
                .orElseThrow(() -> new SupportTicketException(SupportTicketError.UNKNOWN_TICKET));
        if (ticket.getStatus() == SupportTicketStatus.OPEN) return ticket;

        OffsetDateTime now = OffsetDateTime.now();
        User admin = userRepo.findById(adminUserId).orElseThrow(() ->
                new SupportTicketException(SupportTicketError.UNKNOWN_TICKET, "admin missing"));
        ticket.setStatus(SupportTicketStatus.OPEN);
        ticket.setResolvedAt(null);

        SupportTicketMessage system = SupportTicketMessage.builder()
                .ticket(ticket).authorUser(admin)
                .authorRole(SupportTicketAuthorRole.ADMIN)
                .body("Reopened by admin")
                .visibleToUser(true)
                .build();
        messageRepo.save(system);
        ticket.setLastMessageAt(now);
        ticket.setLastMessageAuthor(SupportTicketAuthorRole.ADMIN);
        return ticket;
    }

    /**
     * Assign / unassign the admin owner of a ticket. {@code adminPublicId}
     * null clears the assignment; otherwise the target user must exist and
     * carry the {@link Role#ADMIN} role. Non-admin targets surface as
     * {@code UNKNOWN_TICKET} to avoid leaking role information through the
     * admin assignment surface.
     */
    public SupportTicket assign(UUID ticketPublicId, UUID adminPublicId) {
        SupportTicket ticket = ticketRepo.findByPublicId(ticketPublicId)
                .orElseThrow(() -> new SupportTicketException(SupportTicketError.UNKNOWN_TICKET));
        if (adminPublicId == null) {
            ticket.setAssignedAdminId(null);
            return ticket;
        }
        User admin = userRepo.findByPublicId(adminPublicId).orElseThrow(() ->
                new SupportTicketException(SupportTicketError.UNKNOWN_TICKET, "admin missing"));
        if (admin.getRole() != Role.ADMIN) {
            throw new SupportTicketException(SupportTicketError.UNKNOWN_TICKET, "user is not an admin");
        }
        ticket.setAssignedAdminId(admin.getId());
        return ticket;
    }

    /**
     * Admin-only category re-tag. The original category was set at create
     * time from a user-submitted form, so admins occasionally need to fix
     * misclassifications without rewriting the thread.
     */
    public SupportTicket patchCategory(UUID ticketPublicId, SupportTicketCategory category) {
        if (category == null) {
            throw new SupportTicketException(SupportTicketError.INVALID_CATEGORY, "category required");
        }
        SupportTicket ticket = ticketRepo.findByPublicId(ticketPublicId)
                .orElseThrow(() -> new SupportTicketException(SupportTicketError.UNKNOWN_TICKET));
        ticket.setCategory(category);
        return ticket;
    }

    /**
     * Paginated user-side ticket list. Always scoped to {@code userId} via a
     * mandatory {@code user.id} predicate so a caller can never see another
     * user's rows. Optional filters: {@code status} (exact match) and
     * {@code q} (case-insensitive substring match on {@code subject}).
     * Sort + page size come from the caller-supplied {@link org.springframework.data.domain.Pageable}.
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<SupportTicket> listForUser(
            long userId, SupportTicketStatus status, String q,
            org.springframework.data.domain.Pageable pageable) {
        org.springframework.data.jpa.domain.Specification<SupportTicket> spec =
                (root, cq, cb) -> cb.equal(root.get("user").get("id"), userId);
        if (status != null) {
            var prev = spec;
            spec = (root, cq, cb) -> cb.and(
                    prev.toPredicate(root, cq, cb),
                    cb.equal(root.get("status"), status));
        }
        if (q != null && !q.isBlank()) {
            var prev = spec;
            spec = (root, cq, cb) -> cb.and(
                    prev.toPredicate(root, cq, cb),
                    cb.like(cb.lower(root.get("subject")), "%" + q.toLowerCase() + "%"));
        }
        return ticketRepo.findAll(spec, pageable);
    }

    /**
     * Paginated admin queue list. All filters are optional and AND together.
     * {@code assignee} accepts the literal strings {@code "mine"} (matches
     * {@code assignedAdminId = callerAdminId}), {@code "unassigned"} (matches
     * {@code assignedAdminId IS NULL}), or a submitter user {@code publicId}
     * UUID string (matches {@code user.publicId}). Unparseable assignee
     * values are ignored rather than rejected, so a stale filter chip in the
     * frontend never throws a 500.
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<SupportTicket> listAdmin(
            SupportTicketStatus status, SupportTicketCategory category,
            String assignee, SupportTicketAuthorRole lastAuthor,
            String q, long callerAdminId,
            org.springframework.data.domain.Pageable pageable) {
        org.springframework.data.jpa.domain.Specification<SupportTicket> spec =
                (root, cq, cb) -> cb.conjunction();
        if (status != null) {
            var prev = spec;
            spec = (root, cq, cb) -> cb.and(prev.toPredicate(root, cq, cb),
                    cb.equal(root.get("status"), status));
        }
        if (category != null) {
            var prev = spec;
            spec = (root, cq, cb) -> cb.and(prev.toPredicate(root, cq, cb),
                    cb.equal(root.get("category"), category));
        }
        if (lastAuthor != null) {
            var prev = spec;
            spec = (root, cq, cb) -> cb.and(prev.toPredicate(root, cq, cb),
                    cb.equal(root.get("lastMessageAuthor"), lastAuthor));
        }
        if (assignee != null && !assignee.isBlank()) {
            var prev = spec;
            if ("mine".equalsIgnoreCase(assignee)) {
                spec = (root, cq, cb) -> cb.and(prev.toPredicate(root, cq, cb),
                        cb.equal(root.get("assignedAdminId"), callerAdminId));
            } else if ("unassigned".equalsIgnoreCase(assignee)) {
                spec = (root, cq, cb) -> cb.and(prev.toPredicate(root, cq, cb),
                        cb.isNull(root.get("assignedAdminId")));
            } else {
                java.util.UUID submitterPid;
                try {
                    submitterPid = java.util.UUID.fromString(assignee);
                } catch (IllegalArgumentException e) {
                    submitterPid = null;
                }
                if (submitterPid != null) {
                    final java.util.UUID pid = submitterPid;
                    spec = (root, cq, cb) -> cb.and(prev.toPredicate(root, cq, cb),
                            cb.equal(root.get("user").get("publicId"), pid));
                }
                // unparseable assignee value: ignore the filter (no rows narrowed by it)
            }
        }
        if (q != null && !q.isBlank()) {
            var prev = spec;
            spec = (root, cq, cb) -> cb.and(prev.toPredicate(root, cq, cb),
                    cb.like(cb.lower(root.get("subject")), "%" + q.toLowerCase() + "%"));
        }
        return ticketRepo.findAll(spec, pageable);
    }

    /**
     * Cheap aggregate counters for the admin queue header / sidebar badge.
     * {@code openNeedingAdminReply} matches the "needs admin attention"
     * filter ({@code status = OPEN AND lastMessageAuthor = USER}) so the
     * badge and the filtered list never disagree.
     */
    @Transactional(readOnly = true)
    public SupportTicketQueueStatsDto queueStats() {
        long openNeedingAdminReply = ticketRepo.count((root, cq, cb) -> cb.and(
                cb.equal(root.get("status"), SupportTicketStatus.OPEN),
                cb.equal(root.get("lastMessageAuthor"), SupportTicketAuthorRole.USER)));
        long openTotal = ticketRepo.count((root, cq, cb) ->
                cb.equal(root.get("status"), SupportTicketStatus.OPEN));
        return new SupportTicketQueueStatsDto(openNeedingAdminReply, openTotal);
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
