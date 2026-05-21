package com.slparcelauctions.backend.support;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.support.dto.AdminReplyRequest;
import com.slparcelauctions.backend.support.dto.AdminSupportTicketQueueRow;
import com.slparcelauctions.backend.support.dto.AssignTicketRequest;
import com.slparcelauctions.backend.support.dto.PatchTicketRequest;
import com.slparcelauctions.backend.support.dto.SupportTicketDto;
import com.slparcelauctions.backend.support.dto.SupportTicketMessageDto;
import com.slparcelauctions.backend.support.dto.SupportTicketQueueStatsDto;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Admin-facing support-ticket endpoints. Class-level
 * {@code hasRole('ADMIN')} short-circuits any non-admin caller at 403
 * before service code runs; the {@code /api/v1/admin/**} matcher in
 * {@code SecurityConfig} is the redundant defence-in-depth layer.
 *
 * <p><strong>LazyInit defense:</strong> every method is
 * {@link Transactional @Transactional} (read-only on GET, default on
 * write). The mapper dereferences {@code ticket.messages},
 * {@code message.attachments}, the submitter, and (when present) the
 * assigned admin - all LAZY. The outer transaction keeps the Hibernate
 * session open through the mapper so the response builds without
 * tripping {@code LazyInitializationException} (PR #388 regression).
 *
 * <p><strong>L1-cache flush between write and re-fetch.</strong>
 * Mutating endpoints (resolve / reopen / assign / patch) call a service
 * write and then re-fetch the ticket so the DTO carries the fresh
 * messages graph including any system message the write just appended.
 * Inside a class-level {@code @Transactional} integration test the two
 * service calls share the same persistence context, so the re-fetch
 * would return the cached {@link SupportTicket} entity (whose in-memory
 * {@code messages} set is stale because the service does not
 * bi-directionally hydrate it on save). {@code em.flush() + em.clear()}
 * between the two calls forces the second {@code findByPublicId} to hit
 * the database. In production this is a no-op (each HTTP request is its
 * own transaction); in tests it is a correctness fix.
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-05-21-customer-support-contact-design.md}.
 */
@RestController
@RequestMapping("/api/v1/admin/support-tickets")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminSupportTicketController {

    private final SupportTicketService service;
    private final SupportTicketMapper mapper;
    private final UserRepository userRepo;

    @PersistenceContext
    private EntityManager em;

    @GetMapping
    @Transactional(readOnly = true)
    public PagedResponse<AdminSupportTicketQueueRow> queue(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) SupportTicketStatus status,
            @RequestParam(required = false) SupportTicketCategory category,
            @RequestParam(required = false) String assignee,
            @RequestParam(name = "last_author", required = false) SupportTicketAuthorRole lastAuthor,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<SupportTicket> p = service.listAdmin(status, category, assignee, lastAuthor, q,
                principal.userId(),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "lastMessageAt")));
        return PagedResponse.from(p.map(t -> {
            User admin = t.getAssignedAdminId() == null ? null
                    : userRepo.findById(t.getAssignedAdminId()).orElse(null);
            return mapper.toAdminRow(t, admin);
        }));
    }

    @GetMapping("/queue-stats")
    @Transactional(readOnly = true)
    public SupportTicketQueueStatsDto stats() {
        return service.queueStats();
    }

    @GetMapping("/{publicId}")
    @Transactional(readOnly = true)
    public SupportTicketDto detail(@PathVariable UUID publicId) {
        SupportTicket t = service.findByPublicId(publicId);
        User admin = t.getAssignedAdminId() == null ? null
                : userRepo.findById(t.getAssignedAdminId()).orElse(null);
        return mapper.toAdminDto(t, admin);
    }

    @PostMapping("/{publicId}/messages")
    @Transactional
    public SupportTicketMessageDto reply(@AuthenticationPrincipal AuthPrincipal principal,
                                         @PathVariable UUID publicId,
                                         @Valid @RequestBody AdminReplyRequest req) {
        SupportTicketMessage msg = service.adminReply(principal.userId(), publicId, req);
        return mapper.toMessageDto(msg);
    }

    @PostMapping("/{publicId}/resolve")
    @Transactional
    public SupportTicketDto resolve(@AuthenticationPrincipal AuthPrincipal principal,
                                    @PathVariable UUID publicId) {
        service.resolve(principal.userId(), publicId);
        em.flush();
        em.clear();
        SupportTicket t = service.findByPublicId(publicId);
        User admin = t.getAssignedAdminId() == null ? null
                : userRepo.findById(t.getAssignedAdminId()).orElse(null);
        return mapper.toAdminDto(t, admin);
    }

    @PostMapping("/{publicId}/reopen")
    @Transactional
    public SupportTicketDto reopen(@AuthenticationPrincipal AuthPrincipal principal,
                                   @PathVariable UUID publicId) {
        service.reopen(principal.userId(), publicId);
        em.flush();
        em.clear();
        SupportTicket t = service.findByPublicId(publicId);
        User admin = t.getAssignedAdminId() == null ? null
                : userRepo.findById(t.getAssignedAdminId()).orElse(null);
        return mapper.toAdminDto(t, admin);
    }

    @PostMapping("/{publicId}/assign")
    @Transactional
    public SupportTicketDto assign(@PathVariable UUID publicId,
                                   @Valid @RequestBody AssignTicketRequest req) {
        service.assign(publicId, req.adminPublicId());
        em.flush();
        em.clear();
        SupportTicket t = service.findByPublicId(publicId);
        User admin = t.getAssignedAdminId() == null ? null
                : userRepo.findById(t.getAssignedAdminId()).orElse(null);
        return mapper.toAdminDto(t, admin);
    }

    @PatchMapping("/{publicId}")
    @Transactional
    public SupportTicketDto patch(@PathVariable UUID publicId,
                                  @Valid @RequestBody PatchTicketRequest req) {
        service.patchCategory(publicId, req.category());
        em.flush();
        em.clear();
        SupportTicket t = service.findByPublicId(publicId);
        User admin = t.getAssignedAdminId() == null ? null
                : userRepo.findById(t.getAssignedAdminId()).orElse(null);
        return mapper.toAdminDto(t, admin);
    }
}
