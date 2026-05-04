package com.slparcelauctions.backend.escrow;

import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.exception.AuctionNotFoundException;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.escrow.dto.EscrowDisputeRequest;
import com.slparcelauctions.backend.escrow.dto.EscrowStatusResponse;
import com.slparcelauctions.backend.escrow.dto.SellerEvidenceRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Escrow read + dispute endpoints (spec §4, §8). Visibility is seller-or-winner-only,
 * enforced inside {@link EscrowService} — the controller does not need to re-check
 * membership here because the caller identity is always threaded through
 * {@code principal.userId()} and the service refuses access for other users.
 * Authentication is inherited from the {@code /api/v1/**} catch-all in
 * {@code SecurityConfig} so anonymous requests 401 before ever reaching the handler.
 *
 * <p>{@code auctionPublicId} is a UUID — internal Long PKs are not exposed on
 * the web/mobile API surface.
 */
@RestController
@RequestMapping("/api/v1/auctions/{auctionPublicId}/escrow")
@RequiredArgsConstructor
public class EscrowController {

    private final EscrowService escrowService;
    private final AuctionRepository auctionRepository;

    @GetMapping
    public ResponseEntity<EscrowStatusResponse> getStatus(
            @PathVariable UUID auctionPublicId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        Long auctionId = resolveAuctionId(auctionPublicId);
        return ResponseEntity.ok(escrowService.getStatus(auctionId, principal.userId()));
    }

    @PostMapping(path = "/dispute", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EscrowStatusResponse> fileDispute(
            @PathVariable UUID auctionPublicId,
            @RequestPart("body") @Valid EscrowDisputeRequest body,
            @RequestPart(name = "files", required = false) List<MultipartFile> files,
            @AuthenticationPrincipal AuthPrincipal principal) {
        Long auctionId = resolveAuctionId(auctionPublicId);
        return ResponseEntity.ok(escrowService.fileDispute(
                auctionId, body, principal.userId(),
                files != null ? files : List.of()));
    }

    @PostMapping(path = "/dispute/seller-evidence",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EscrowStatusResponse> submitSellerEvidence(
            @PathVariable UUID auctionPublicId,
            @RequestPart("body") @Valid SellerEvidenceRequest body,
            @RequestPart(name = "files", required = false) List<MultipartFile> files,
            @AuthenticationPrincipal AuthPrincipal principal) {
        Long auctionId = resolveAuctionId(auctionPublicId);
        Long escrowId = escrowService.findEscrowIdByAuctionId(auctionId);
        return ResponseEntity.ok(escrowService.submitSellerEvidence(
                escrowId, principal.userId(), body,
                files != null ? files : List.of()));
    }

    private Long resolveAuctionId(UUID auctionPublicId) {
        return auctionRepository.findByPublicId(auctionPublicId)
                .map(Auction::getId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionPublicId));
    }
}
