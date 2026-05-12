package com.slparcelauctions.backend.realty.wallet;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.exception.GroupDissolvedException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;
import com.slparcelauctions.backend.realty.wallet.dto.GroupLedgerEntryDto;
import com.slparcelauctions.backend.realty.wallet.dto.GroupWalletDto;
import com.slparcelauctions.backend.realty.wallet.dto.GroupWalletDtoMapper;
import com.slparcelauctions.backend.realty.wallet.dto.GroupWithdrawRequest;
import com.slparcelauctions.backend.realty.wallet.dto.GroupWithdrawResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * HTTP surface for the realty-group wallet. Spec §5.1, §5.2, §5.3.
 *
 * <p>All endpoints require JWT. Permission checks delegate to
 * {@link RealtyGroupAuthorizer} (leader auto-passes; delegates require
 * the relevant permission on their membership row).
 *
 * <p>This class is inside {@code com.slparcelauctions.backend.realty.wallet},
 * which is a sub-package of {@code com.slparcelauctions.backend.realty}.
 * {@link com.slparcelauctions.backend.realty.exception.RealtyExceptionHandler}
 * is annotated with {@code @RestControllerAdvice(basePackages =
 * "com.slparcelauctions.backend.realty")} — that scope includes this
 * sub-package, so all realty wallet exceptions are handled there.
 */
@RestController
@RequestMapping("/api/v1/realty/groups/{publicId}/wallet")
@RequiredArgsConstructor
public class RealtyGroupWalletController {

    private static final int RECENT_LEDGER_LIMIT = 50;
    private static final int DEFAULT_LEDGER_LIMIT = 50;
    private static final int MAX_LEDGER_LIMIT = 100;

    private final RealtyGroupRepository groupRepository;
    private final RealtyGroupLedgerRepository ledgerRepository;
    private final RealtyGroupAuthorizer authorizer;
    private final RealtyGroupWalletService walletService;
    private final GroupWalletDtoMapper mapper;

    // ─────────────────────────────────────────────────────────────────
    // GET /api/v1/realty/groups/{publicId}/wallet
    // ─────────────────────────────────────────────────────────────────

    /**
     * Returns the group's current wallet summary with the 50 most-recent ledger entries.
     * Requires {@code VIEW_GROUP_TRANSACTIONS} (leader auto-passes). Spec §5.1.
     */
    @GetMapping
    @Transactional(readOnly = true)
    public GroupWalletDto getWallet(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        RealtyGroup g = loadGroup(publicId);
        authorizer.assertCan(principal.userId(), g.getId(), RealtyGroupPermission.VIEW_GROUP_TRANSACTIONS);

        List<RealtyGroupLedgerEntry> recent = ledgerRepository.findRecentForGroup(
            g.getId(), PageRequest.of(0, RECENT_LEDGER_LIMIT));

        return new GroupWalletDto(
            g.getBalanceLindens(),
            g.getReservedLindens(),
            g.availableLindens(),
            mapper.toDtos(recent));
    }

    // ─────────────────────────────────────────────────────────────────
    // GET /api/v1/realty/groups/{publicId}/wallet/ledger?cursor=...&limit=...
    // ─────────────────────────────────────────────────────────────────

    /**
     * Cursor-paginated ledger. {@code cursor} is an ISO-8601 {@code OffsetDateTime};
     * entries older than the cursor are returned, newest-first.
     * Limit is clamped to [1, 100], default 50. Spec §5.2.
     */
    @GetMapping("/ledger")
    @Transactional(readOnly = true)
    public List<GroupLedgerEntryDto> getLedger(
            @PathVariable UUID publicId,
            @RequestParam(required = false) OffsetDateTime cursor,
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal AuthPrincipal principal) {
        RealtyGroup g = loadGroup(publicId);
        authorizer.assertCan(principal.userId(), g.getId(), RealtyGroupPermission.VIEW_GROUP_TRANSACTIONS);

        int clampedLimit = Math.max(1, Math.min(limit, MAX_LEDGER_LIMIT));
        List<RealtyGroupLedgerEntry> entries;
        if (cursor == null) {
            entries = ledgerRepository.findRecentForGroup(
                g.getId(), PageRequest.of(0, clampedLimit));
        } else {
            entries = ledgerRepository.findOlderForGroup(
                g.getId(), cursor, PageRequest.of(0, clampedLimit));
        }
        return mapper.toDtos(entries);
    }

    // ─────────────────────────────────────────────────────────────────
    // POST /api/v1/realty/groups/{publicId}/wallet/withdraw
    // ─────────────────────────────────────────────────────────────────

    /**
     * Initiate a group-wallet withdrawal. Recipient is always the group leader's
     * verified SL avatar, regardless of who calls this endpoint. Spec §5.3.
     *
     * <p>Validation order:
     * <ol>
     *   <li>Load group → 404 if unknown.</li>
     *   <li>Check dissolved → 410 if {@code dissolvedAt != null}.</li>
     *   <li>Permission check → 403 if missing {@code WITHDRAW_FROM_GROUP_WALLET}.</li>
     *   <li>Delegate to {@link RealtyGroupWalletService#withdraw} (handles terms, frozen,
     *       balance, idempotency, terminal command). Service exceptions propagate to
     *       {@link com.slparcelauctions.backend.realty.exception.RealtyExceptionHandler}.</li>
     * </ol>
     */
    @PostMapping("/withdraw")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public GroupWithdrawResponse withdraw(
            @PathVariable UUID publicId,
            @Valid @RequestBody GroupWithdrawRequest req,
            @AuthenticationPrincipal AuthPrincipal principal) {
        RealtyGroup g = groupRepository.findByPublicId(publicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(publicId));

        if (g.getDissolvedAt() != null) {
            throw new GroupDissolvedException(publicId);
        }

        authorizer.assertCan(principal.userId(), g.getId(),
            RealtyGroupPermission.WITHDRAW_FROM_GROUP_WALLET);

        RealtyGroupWalletService.WithdrawResult result = walletService.withdraw(
            g.getId(), req.amount(), req.idempotencyKey(), principal.userId());

        return new GroupWithdrawResponse(result.queueId(), result.estimatedFulfillmentSeconds());
    }

    // ─────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────

    /**
     * Loads a group by public UUID, throwing 404 if absent.
     * Does not check dissolution — callers that care about dissolved state
     * perform their own check after loading.
     */
    private RealtyGroup loadGroup(UUID publicId) {
        return groupRepository.findByPublicId(publicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(publicId));
    }
}
