package com.slparcelauctions.backend.escrow;

import java.util.List;

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

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.escrow.dto.EscrowDisputeRequest;
import com.slparcelauctions.backend.escrow.dto.EscrowStatusResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Escrow read + dispute endpoints (spec §4, §8). Visibility is seller-or-winner-only,
 * enforced inside {@link EscrowService} — the controller does not need to re-check
 * membership here because the caller identity is always threaded through
 * {@code principal.userId()} and the service refuses access for other users.
 * Authentication is inherited from the {@code /api/v1/**} catch-all in
 * {@code SecurityConfig} so anonymous requests 401 before ever reaching the handler.
 */
@RestController
@RequestMapping("/api/v1/auctions/{auctionId}/escrow")
@RequiredArgsConstructor
public class EscrowController {

    private final EscrowService escrowService;

    @GetMapping
    public ResponseEntity<EscrowStatusResponse> getStatus(
            @PathVariable Long auctionId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(escrowService.getStatus(auctionId, principal.userId()));
    }

    @PostMapping(path = "/dispute", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EscrowStatusResponse> fileDispute(
            @PathVariable Long auctionId,
            @RequestPart("body") @Valid EscrowDisputeRequest body,
            @RequestPart(name = "files", required = false) List<MultipartFile> files,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(escrowService.fileDispute(
                auctionId, body, principal.userId(),
                files != null ? files : List.of()));
    }
}
