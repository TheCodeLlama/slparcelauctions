package com.slparcelauctions.backend.realty.wallet.admin;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.realty.wallet.admin.dto.AdminWalletAdjustRequest;
import com.slparcelauctions.backend.realty.wallet.dto.GroupWalletDto;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Sub-project G section 7.2 -- admin realty group wallet adjustment surface.
 * Single endpoint; signed-amount payload; service writes ledger + audit + broadcast
 * as one transactional unit.
 *
 * <p>{@code @PreAuthorize("hasRole('ADMIN')")} is defense-in-depth alongside the
 * {@code /api/v1/admin/**} security-chain gate -- both must agree, so a future
 * misroute or new path mapping cannot accidentally drop the admin requirement.
 *
 * <p>Exception translation is delegated to {@link
 * com.slparcelauctions.backend.realty.exception.RealtyExceptionHandler}: it
 * maps {@code AdminAdjustAmountOutOfRangeException} and the shared wallet
 * exceptions ({@code InsufficientGroupBalanceException},
 * {@code RealtyGroupNotFoundException}, etc.) to their respective HTTP shapes.
 * Zero-amount and >500-char reason are caught earlier -- the request DTO
 * validation surfaces those as 400 before the call ever reaches the service.
 */
@RestController
@RequestMapping("/api/v1/admin/realty-groups/{publicId}/wallet")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminRealtyGroupWalletController {

    private final AdminRealtyGroupWalletService service;

    @PostMapping("/adjust")
    public GroupWalletDto adjust(
            @PathVariable UUID publicId,
            @Valid @RequestBody AdminWalletAdjustRequest req,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return service.adjust(principal.userId(), publicId, req.amount(), req.reason());
    }
}
