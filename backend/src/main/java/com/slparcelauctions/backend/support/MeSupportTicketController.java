package com.slparcelauctions.backend.support;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.support.dto.CreateSupportTicketRequest;
import com.slparcelauctions.backend.support.dto.ReplySupportTicketRequest;
import com.slparcelauctions.backend.support.dto.SupportTicketDto;
import com.slparcelauctions.backend.support.dto.SupportTicketMessageDto;
import com.slparcelauctions.backend.support.dto.SupportTicketSummaryDto;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * User-facing support-ticket endpoints. Authentication is the global
 * {@code /api/v1/**} catch-all (any logged-in user); ownership is
 * enforced inside the service via
 * {@link SupportTicketService#findByPublicIdEnsureOwner(long, UUID)} so
 * a caller can never read or reply to another user's thread (mismatches
 * surface as {@link SupportTicketError#UNKNOWN_TICKET} rather than a
 * distinct forbidden code, to avoid leaking ticket existence).
 *
 * <p><strong>LazyInit defense:</strong> every method is
 * {@link Transactional @Transactional} (read-only on GET, default on
 * write). The mapper dereferences
 * {@code ticket.messages} / {@code message.attachments} / the submitter
 * and assigned-admin {@link User} graphs, all of which are LAZY. The
 * outer transaction keeps the Hibernate session open through the mapper
 * so the response builds without tripping
 * {@code LazyInitializationException} - the regression that wrecked
 * {@code GET /api/v1/admin/coupons/{publicId}} in PR #388 and the
 * pattern enforced for every entity-derived controller since.
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-05-21-customer-support-contact-design.md}.
 */
@RestController
@RequestMapping("/api/v1/me/support-tickets")
@RequiredArgsConstructor
public class MeSupportTicketController {

    private final SupportTicketService service;
    private final SupportTicketMapper mapper;
    private final UserRepository userRepo;

    /**
     * Used inside {@link #create} to evict the just-persisted entities
     * from the L1 cache before the re-fetch. Without the {@code clear()},
     * the second {@code findByPublicIdEnsureOwner} call would return the
     * cached {@link SupportTicket} (whose in-memory {@code messages} set
     * is still empty because the service does not bi-directionally
     * hydrate it on save), and the response would be missing the initial
     * message. In production this is a no-op - each request is its own
     * transaction - but inside a class-level {@code @Transactional} test
     * the cache survives across the controller's two service calls.
     */
    @PersistenceContext
    private EntityManager em;

    @GetMapping
    @Transactional(readOnly = true)
    public PagedResponse<SupportTicketSummaryDto> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) SupportTicketStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<SupportTicket> p = service.listForUser(principal.userId(), status, q,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "lastMessageAt")));
        return PagedResponse.from(p.map(mapper::toSummaryDto));
    }

    @GetMapping("/{publicId}")
    @Transactional(readOnly = true)
    public SupportTicketDto detail(@AuthenticationPrincipal AuthPrincipal principal,
                                   @PathVariable UUID publicId) {
        SupportTicket t = service.findByPublicIdEnsureOwner(principal.userId(), publicId);
        User assignedAdmin = t.getAssignedAdminId() == null ? null
                : userRepo.findById(t.getAssignedAdminId()).orElse(null);
        return mapper.toUserDto(t, assignedAdmin);
    }

    @PostMapping
    @Transactional
    public SupportTicketDto create(@AuthenticationPrincipal AuthPrincipal principal,
                                   @Valid @RequestBody CreateSupportTicketRequest req) {
        SupportTicket t = service.createTicket(principal.userId(), req);
        UUID createdPublicId = t.getPublicId();
        // Force a flush + clear so the re-fetch hits the database and
        // rebuilds the entity graph with messages + attachments
        // eagerly populated, instead of returning the cached entity
        // (whose in-memory messages set is empty post-save).
        em.flush();
        em.clear();
        SupportTicket reloaded = service.findByPublicIdEnsureOwner(
                principal.userId(), createdPublicId);
        return mapper.toUserDto(reloaded, null);
    }

    @PostMapping("/{publicId}/messages")
    @Transactional
    public SupportTicketMessageDto reply(@AuthenticationPrincipal AuthPrincipal principal,
                                         @PathVariable UUID publicId,
                                         @Valid @RequestBody ReplySupportTicketRequest req) {
        // Pre-check ownership: userReply lookups via findByPublicId which
        // already throws UNKNOWN_TICKET on a missing row OR a wrong owner,
        // so a caller probing for another user's ticket sees the same 404
        // shape as "ticket does not exist".
        SupportTicketMessage msg = service.userReply(principal.userId(), publicId, req);
        return mapper.toMessageDto(msg);
    }
}
