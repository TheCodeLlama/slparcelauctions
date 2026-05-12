package com.slparcelauctions.backend.realty.wallet.admin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.exception.GroupDissolvedException;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntry;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntryType;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerRepository;
import com.slparcelauctions.backend.realty.wallet.broadcast.GroupWalletBroadcastPublisher;
import com.slparcelauctions.backend.realty.wallet.dto.GroupWalletDto;
import com.slparcelauctions.backend.realty.wallet.dto.GroupWalletDtoMapper;
import com.slparcelauctions.backend.realty.wallet.exception.AdminAdjustAmountOutOfRangeException;
import com.slparcelauctions.backend.realty.wallet.exception.InsufficientGroupBalanceException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sub-project G section 7.2 -- admin-initiated realty group wallet adjustment.
 * Single entry point with a signed amount (positive = credit, negative = debit)
 * and a freeform reason. Writes one {@code ADMIN_ADJUSTMENT} ledger row, mutates
 * {@code realty_groups.balance_lindens} atomically under a pessimistic-write
 * lock, writes a paired {@code REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT} audit row,
 * and broadcasts the balance change on the group's WebSocket topic.
 *
 * <p>Validation:
 * <ul>
 *   <li>{@code amount != 0}</li>
 *   <li>{@code |amount| <= slpa.realty.admin-wallet-adjust-max-l} (default 10M)</li>
 *   <li>{@code reason} non-blank, <= 500 chars</li>
 *   <li>resulting balance must be >= 0 on a debit</li>
 *   <li>group must exist and not be dissolved</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminRealtyGroupWalletService {

    static final int MAX_REASON_LENGTH = 500;
    private static final int RECENT_LEDGER_LIMIT = 50;

    private final RealtyGroupRepository groupRepository;
    private final RealtyGroupLedgerRepository ledgerRepository;
    private final UserRepository userRepository;
    private final AdminActionService adminActionService;
    private final GroupWalletBroadcastPublisher broadcastPublisher;
    private final GroupWalletDtoMapper walletDtoMapper;
    private final AdminWalletAdjustProperties props;

    /**
     * Apply a signed-amount adjustment to the group's wallet.
     *
     * @param adminUserId  the admin actor (recorded on the ledger row and audit row)
     * @param groupPublicId the target group's public UUID
     * @param amount       signed L$ amount (positive credit, negative debit, non-zero)
     * @param reason       non-blank freeform reason, <= 500 chars
     * @return updated wallet DTO including the latest 50 ledger entries
     */
    @Transactional
    public GroupWalletDto adjust(Long adminUserId, UUID groupPublicId, long amount, String reason) {
        if (amount == 0L) {
            throw new IllegalArgumentException("amount must be non-zero");
        }
        long ceiling = props.getAdminWalletAdjustMaxL();
        if (Math.abs(amount) > ceiling) {
            throw new AdminAdjustAmountOutOfRangeException(amount, ceiling);
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must be non-blank");
        }
        String trimmed = reason.strip();
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("reason exceeds " + MAX_REASON_LENGTH + " chars");
        }

        // Lookup-by-publicId to surface 404 vs 410 distinction; the actual
        // balance mutation reacquires under pessimistic write lock by id.
        RealtyGroup g = groupRepository.findByPublicId(groupPublicId)
                .orElseThrow(() -> new RealtyGroupNotFoundException(groupPublicId));
        if (g.getDissolvedAt() != null) {
            throw new GroupDissolvedException(groupPublicId);
        }

        RealtyGroup locked = groupRepository.findByIdForUpdate(g.getId()).orElseThrow();
        long newBalance = locked.getBalanceLindens() + amount;
        if (newBalance < 0L) {
            // Section 7.2 -- a debit may not push balance below zero.
            throw new InsufficientGroupBalanceException(locked.availableLindens(), Math.abs(amount));
        }
        locked.setBalanceLindens(newBalance);
        groupRepository.save(locked);

        RealtyGroupLedgerEntry entry = ledgerRepository.save(RealtyGroupLedgerEntry.builder()
                .groupId(locked.getId())
                .entryType(RealtyGroupLedgerEntryType.ADMIN_ADJUSTMENT)
                .amount(amount)
                .balanceAfter(newBalance)
                .reservedAfter(locked.getReservedLindens())
                .description(trimmed)
                .actorUserId(adminUserId)
                .createdByAdminId(adminUserId)
                .build());

        adminActionService.record(
                adminUserId,
                AdminActionType.REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT,
                AdminActionTargetType.REALTY_GROUP,
                locked.getId(),
                null,
                Map.of("amount", amount, "reason", trimmed));

        broadcastPublisher.publish(locked.getPublicId(),
                newBalance, locked.getReservedLindens(), locked.availableLindens(),
                RealtyGroupLedgerEntryType.ADMIN_ADJUSTMENT.name(), entry.getPublicId());

        log.info("admin wallet adjustment: groupId={}, amount={}, balanceAfter={}, admin={}",
                locked.getId(), amount, newBalance, adminUserId);

        User leader = userRepository.findById(locked.getLeaderId()).orElseThrow();
        List<RealtyGroupLedgerEntry> recent = ledgerRepository.findRecentForGroup(
                locked.getId(), PageRequest.of(0, RECENT_LEDGER_LIMIT));
        return walletDtoMapper.toWalletDto(
                locked.getBalanceLindens(),
                locked.getReservedLindens(),
                locked.availableLindens(),
                leader,
                recent);
    }
}
