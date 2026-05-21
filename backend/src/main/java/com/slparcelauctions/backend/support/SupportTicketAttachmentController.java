package com.slparcelauctions.backend.support;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Attachment lifecycle endpoints for the support-ticket feature.
 *
 * <p>Two endpoints, both gated by the global {@code /api/v1/**}
 * authenticated catch-all (any logged-in user can call):
 *
 * <ul>
 *   <li>{@code POST /api/v1/me/support-tickets/attachments} stages an
 *       image upload via
 *       {@link SupportTicketAttachmentService#preUpload(User, MultipartFile)}
 *       and returns an opaque {@code attachmentKey} the client then
 *       includes on a subsequent create-ticket / reply request. The
 *       service writes to {@code support-attachments/pending/...} and
 *       caches metadata in Redis with a TTL so abandoned uploads
 *       self-clean (alongside the S3 bucket lifecycle rule documented in
 *       the spec's "Deploy notes" section).</li>
 *   <li>{@code GET /api/v1/support-tickets/attachments/{publicId}}
 *       returns a short-lived presigned download URL for a promoted
 *       attachment. Authorization is the ticket owner OR any admin -
 *       enforced inline since attachments are read across both user and
 *       admin surfaces and we want a single endpoint for both.</li>
 * </ul>
 *
 * <p><strong>LazyInit defense:</strong> the signed-URL GET is wrapped in
 * {@link Transactional @Transactional(readOnly = true)} because the
 * authorization check walks a chain of LAZY associations
 * ({@code attachment -> message -> ticket -> user}) and would otherwise
 * trip {@code LazyInitializationException} - same pattern as the rest of
 * the support controllers.
 */
@RestController
@RequiredArgsConstructor
public class SupportTicketAttachmentController {

    private final SupportTicketAttachmentService attachmentService;
    private final SupportTicketAttachmentRepository attachmentRepo;
    private final UserRepository userRepo;

    @PostMapping(value = "/api/v1/me/support-tickets/attachments",
                  consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttachmentKeyResponse preUpload(@AuthenticationPrincipal AuthPrincipal principal,
                                             @RequestParam("file") MultipartFile file) {
        User u = userRepo.findById(principal.userId()).orElseThrow(() ->
                new SupportTicketException(SupportTicketError.UNKNOWN_TICKET, "user missing"));
        String key = attachmentService.preUpload(u, file);
        return new AttachmentKeyResponse(key);
    }

    @GetMapping("/api/v1/support-tickets/attachments/{publicId}")
    @Transactional(readOnly = true)
    public AttachmentSignedUrlResponse signedUrl(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID publicId) {
        SupportTicketAttachment att = attachmentRepo.findByPublicId(publicId)
                .orElseThrow(() -> new SupportTicketException(SupportTicketError.ATTACHMENT_NOT_FOUND));
        // Authorization: ticket owner OR any admin. The LAZY chain
        // attachment -> message -> ticket -> user is dereferenced inside
        // the @Transactional boundary so the Hibernate session is open.
        Long ticketUserId = att.getMessage().getTicket().getUser().getId();
        User caller = userRepo.findById(principal.userId()).orElseThrow(() ->
                new SupportTicketException(SupportTicketError.UNKNOWN_TICKET, "user missing"));
        boolean isOwner = ticketUserId.equals(principal.userId());
        boolean isAdmin = caller.getRole() == Role.ADMIN;
        if (!isOwner && !isAdmin) {
            throw new SupportTicketException(SupportTicketError.NOT_OWNER);
        }
        return new AttachmentSignedUrlResponse(attachmentService.signedDownloadUrl(att));
    }

    public record AttachmentKeyResponse(String attachmentKey) {}
    public record AttachmentSignedUrlResponse(String url) {}
}
