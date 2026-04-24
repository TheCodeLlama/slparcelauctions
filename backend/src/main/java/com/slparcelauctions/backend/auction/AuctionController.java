package com.slparcelauctions.backend.auction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auction.dto.AuctionCancelRequest;
import com.slparcelauctions.backend.auction.dto.AuctionCreateRequest;
import com.slparcelauctions.backend.auction.dto.AuctionUpdateRequest;
import com.slparcelauctions.backend.auction.dto.AuctionVerifyRequest;
import com.slparcelauctions.backend.auction.dto.PendingVerification;
import com.slparcelauctions.backend.auction.dto.PublicAuctionResponse;
import com.slparcelauctions.backend.auction.dto.SellerAuctionResponse;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.escrow.Escrow;
import com.slparcelauctions.backend.escrow.EscrowRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Auction CRUD endpoints. Write paths (create/update/cancel) require the caller to be
 * SL-verified — enforced inline by {@link #requireVerified(Long)} before delegating to
 * services. Reads do not require verification: the public view
 * ({@link PublicAuctionResponse}) is available to any authenticated user, and statuses
 * that must stay private to the seller (pre-ACTIVE drafts plus SUSPENDED listings —
 * spec §6.4) 404 to non-sellers to avoid leaking existence.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AuctionController {

    private final AuctionService auctionService;
    private final AuctionVerificationService verificationService;
    private final CancellationService cancellationService;
    private final AuctionDtoMapper mapper;
    private final UserRepository userRepository;
    private final EscrowRepository escrowRepository;

    @PostMapping("/auctions")
    @ResponseStatus(HttpStatus.CREATED)
    public SellerAuctionResponse create(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody AuctionCreateRequest req) {
        requireVerified(principal.userId());
        Auction created = auctionService.create(principal.userId(), req);
        return mapper.toSellerResponse(created, null);
    }

    @GetMapping("/auctions/{id}")
    public Object get(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        // loadForDetail eagerly hydrates parcel + seller + photos + tags so
        // the public/seller mappers downstream can render the seller card +
        // photo carousel off a single LEFT JOIN. Single-row fetch — no
        // HHH90003004 risk from the multiple to-many entity-graph branches.
        Auction a = auctionService.loadForDetail(id);
        Long userId = principal == null ? null : principal.userId();
        boolean isSeller = userId != null && a.getSeller().getId().equals(userId);
        if (!isSeller) {
            if (isHiddenFromPublic(a.getStatus())) {
                throw new AuctionNotFoundException(id); // hide existence
            }
            return mapper.toPublicResponse(a);
        }
        return mapper.toSellerResponse(a, null);
    }

    @GetMapping("/users/me/auctions")
    public List<SellerAuctionResponse> listMine(
            @AuthenticationPrincipal AuthPrincipal principal) {
        List<Auction> auctions = auctionService.loadOwnedBy(principal.userId());
        Map<Long, Escrow> escrows = loadEscrowsFor(auctions);
        return auctions.stream()
                .map(a -> mapper.toSellerResponse(a, null, escrows.get(a.getId())))
                .toList();
    }

    @PutMapping("/auctions/{id}")
    public SellerAuctionResponse update(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody AuctionUpdateRequest req) {
        requireVerified(principal.userId());
        Auction updated = auctionService.update(id, principal.userId(), req);
        return mapper.toSellerResponse(updated, null);
    }

    @PutMapping("/auctions/{id}/verify")
    public SellerAuctionResponse verify(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody AuctionVerifyRequest body) {
        Long userId = principal.userId();
        requireVerified(userId);
        Auction a = verificationService.triggerVerification(id, body.method(), userId);
        PendingVerification pending = verificationService.buildPendingVerification(a);
        return mapper.toSellerResponse(a, pending);
    }

    @PutMapping("/auctions/{id}/cancel")
    public SellerAuctionResponse cancel(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody AuctionCancelRequest req) {
        requireVerified(principal.userId());
        // Non-locking load authorises the seller; the service re-fetches under
        // a pessimistic write lock for the state transition so cancellation
        // races with bid placement / auction end serialise on the row lock.
        auctionService.loadForSeller(id, principal.userId());
        Auction cancelled = cancellationService.cancel(id, req.reason());
        return mapper.toSellerResponse(cancelled, null);
    }

    @GetMapping("/auctions/{id}/preview")
    public SellerAuctionResponse preview(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        Auction a = auctionService.loadForSeller(id, principal.userId());
        return mapper.toSellerResponse(a, null);
    }

    /**
     * Public-profile active-listings endpoint (spec §14). Anonymous access is
     * allowed — the response shape is {@link PublicAuctionResponse}, so no
     * seller-only fields leak. SUSPENDED and pre-ACTIVE statuses are excluded
     * at the repository level regardless of requester identity.
     *
     * <p>Phase 1 only exposes {@code status=ACTIVE}; any other value is
     * rejected with a 400 via {@link IllegalArgumentException} so callers get
     * a clear signal while the endpoint's surface grows later (Epic 07 Browse).
     * The default page size of 6 matches the
     * {@code ActiveListingsSection} grid on the public profile page — callers
     * can override with {@code size=...}.
     *
     * <p><strong>Permissive on unknown {@code userId}:</strong> a nonexistent
     * {@code userId} resolves to an empty page (200), not a 404. This is a
     * deliberate privacy choice — returning 404 for missing users would let
     * callers enumerate valid user IDs by diffing status codes against this
     * public surface.
     */
    @GetMapping("/users/{userId}/auctions")
    public PagedResponse<PublicAuctionResponse> getUserAuctions(
            @PathVariable Long userId,
            @RequestParam(name = "status") String status,
            @PageableDefault(size = 6) Pageable pageable) {
        if (!"ACTIVE".equals(status)) {
            throw new IllegalArgumentException(
                    "Unsupported status filter: '" + status
                            + "'. Only 'ACTIVE' is supported.");
        }
        // ACTIVE-only filter today means no escrow rows exist for this page,
        // but we still batch-load so the response shape stays consistent when
        // status filtering expands (Epic 07). One query per page beats one
        // per row once ENDED variants become reachable here.
        var page = auctionService.loadActiveBySeller(userId, pageable);
        Map<Long, Escrow> escrows = loadEscrowsFor(page.getContent());
        return PagedResponse.from(page.map(a ->
                mapper.toPublicResponse(a, escrows.get(a.getId()))));
    }

    /**
     * Batch-loads escrows for a collection of auctions, keyed by auction id.
     * Returns an empty map for empty inputs. Auctions without an escrow row
     * are simply absent from the map (callers pass the missing lookup as
     * null to the mapper overloads).
     */
    private Map<Long, Escrow> loadEscrowsFor(List<Auction> auctions) {
        if (auctions.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = auctions.stream().map(Auction::getId).filter(id -> id != null).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Escrow> byAuctionId = new HashMap<>();
        for (Escrow e : escrowRepository.findByAuctionIdIn(ids)) {
            Long aId = e.getAuction() == null ? null : e.getAuction().getId();
            if (aId != null) {
                byAuctionId.put(aId, e);
            }
        }
        return byAuctionId;
    }

    /**
     * Statuses that must 404 for non-sellers on direct URL access. Covers
     * pre-ACTIVE drafts (leaking DRAFT/verification existence would expose
     * seller prep work) and SUSPENDED listings (spec §6.4 — suspended
     * listings are hidden from public browse, and direct-URL access must
     * match that semantics rather than collapse to ENDED).
     */
    private boolean isHiddenFromPublic(AuctionStatus s) {
        return s == AuctionStatus.DRAFT
                || s == AuctionStatus.DRAFT_PAID
                || s == AuctionStatus.VERIFICATION_PENDING
                || s == AuctionStatus.VERIFICATION_FAILED
                || s == AuctionStatus.SUSPENDED;
    }

    private void requireVerified(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        if (!Boolean.TRUE.equals(user.getVerified())) {
            throw new AccessDeniedException(
                    "SL avatar verification required to manage auctions.");
        }
    }
}
