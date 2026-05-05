package com.slparcelauctions.backend.admin.users.wallet;

import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.admin.users.wallet.dto.AdminLedgerRowDto;
import com.slparcelauctions.backend.admin.users.wallet.dto.AdminWalletAdjustRequest;
import com.slparcelauctions.backend.admin.users.wallet.dto.AdminWalletForgivePenaltyRequest;
import com.slparcelauctions.backend.admin.users.wallet.dto.AdminWalletNotesRequest;
import com.slparcelauctions.backend.admin.users.wallet.dto.AdminWalletSnapshotDto;
import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Admin per-user wallet ops. URL paths use the user's {@code publicId} (UUID)
 * to match the rest of the admin/users surface; all internal lookups resolve
 * to the user's internal {@code Long id} via {@link UserRepository#findByPublicId}.
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-05-05-admin-user-wallet-ops-design.md}.
 */
@RestController
@RequestMapping("/api/v1/admin/users/{publicId}/wallet")
@RequiredArgsConstructor
public class AdminWalletController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminWalletService walletService;
    private final UserRepository userRepository;

    @GetMapping
    public AdminWalletSnapshotDto snapshot(@PathVariable UUID publicId) {
        return walletService.snapshot(resolveUserId(publicId));
    }

    @GetMapping("/ledger")
    public PagedResponse<AdminLedgerRowDto> ledger(
            @PathVariable UUID publicId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return walletService.ledger(
                resolveUserId(publicId),
                PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)));
    }

    @PostMapping("/adjust")
    public AdminWalletSnapshotDto adjust(
            @PathVariable UUID publicId,
            @Valid @RequestBody AdminWalletAdjustRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        return walletService.adjust(resolveUserId(publicId), body, admin.userId());
    }

    @PostMapping("/freeze")
    public AdminWalletSnapshotDto freeze(
            @PathVariable UUID publicId,
            @Valid @RequestBody AdminWalletNotesRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        return walletService.freeze(resolveUserId(publicId), body, admin.userId());
    }

    @PostMapping("/unfreeze")
    public AdminWalletSnapshotDto unfreeze(
            @PathVariable UUID publicId,
            @Valid @RequestBody AdminWalletNotesRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        return walletService.unfreeze(resolveUserId(publicId), body, admin.userId());
    }

    @PostMapping("/forgive-penalty")
    public AdminWalletSnapshotDto forgivePenalty(
            @PathVariable UUID publicId,
            @Valid @RequestBody AdminWalletForgivePenaltyRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        return walletService.forgivePenalty(resolveUserId(publicId), body, admin.userId());
    }

    @PostMapping("/reset-dormancy")
    public AdminWalletSnapshotDto resetDormancy(
            @PathVariable UUID publicId,
            @Valid @RequestBody AdminWalletNotesRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        return walletService.resetDormancy(resolveUserId(publicId), body, admin.userId());
    }

    @PostMapping("/clear-terms")
    public AdminWalletSnapshotDto clearTerms(
            @PathVariable UUID publicId,
            @Valid @RequestBody AdminWalletNotesRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        return walletService.clearTerms(resolveUserId(publicId), body, admin.userId());
    }

    @PostMapping("/withdrawals/{terminalCommandId}/force-complete")
    public AdminWalletSnapshotDto forceCompleteWithdrawal(
            @PathVariable UUID publicId,
            @PathVariable Long terminalCommandId,
            @Valid @RequestBody AdminWalletNotesRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        return walletService.forceCompleteWithdrawal(
                resolveUserId(publicId), terminalCommandId, body, admin.userId());
    }

    @PostMapping("/withdrawals/{terminalCommandId}/force-fail")
    public AdminWalletSnapshotDto forceFailWithdrawal(
            @PathVariable UUID publicId,
            @PathVariable Long terminalCommandId,
            @Valid @RequestBody AdminWalletNotesRequest body,
            @AuthenticationPrincipal AuthPrincipal admin) {
        return walletService.forceFailWithdrawal(
                resolveUserId(publicId), terminalCommandId, body, admin.userId());
    }

    private Long resolveUserId(UUID publicId) {
        return userRepository.findByPublicId(publicId)
                .map(User::getId)
                .orElseThrow(() -> new UserNotFoundException(publicId));
    }
}
