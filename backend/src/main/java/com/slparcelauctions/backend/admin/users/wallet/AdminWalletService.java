package com.slparcelauctions.backend.admin.users.wallet;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.admin.audit.AdminActionService;
import com.slparcelauctions.backend.admin.audit.AdminActionTargetType;
import com.slparcelauctions.backend.admin.audit.AdminActionType;
import com.slparcelauctions.backend.admin.users.wallet.dto.AdminLedgerRowDto;
import com.slparcelauctions.backend.admin.users.wallet.dto.AdminPendingWithdrawalDto;
import com.slparcelauctions.backend.admin.users.wallet.dto.AdminWalletAdjustRequest;
import com.slparcelauctions.backend.admin.users.wallet.dto.AdminWalletForgivePenaltyRequest;
import com.slparcelauctions.backend.admin.users.wallet.dto.AdminWalletNotesRequest;
import com.slparcelauctions.backend.admin.users.wallet.dto.AdminWalletSnapshotDto;
import com.slparcelauctions.backend.admin.users.wallet.exception.AdminWalletStateException;
import com.slparcelauctions.backend.common.PagedResponse;
import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.escrow.command.TerminalCommandStatus;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.UserLedgerEntry;
import com.slparcelauctions.backend.wallet.UserLedgerEntryType;
import com.slparcelauctions.backend.wallet.UserLedgerRepository;
import com.slparcelauctions.backend.wallet.WalletService;
import com.slparcelauctions.backend.wallet.broadcast.WalletBroadcastPublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Admin per-user wallet operations. See spec
 * docs/superpowers/specs/2026-05-05-admin-user-wallet-ops-design.md.
 *
 * <p>Each mutation locks the relevant row pessimistically, validates state,
 * performs the change, writes an {@link com.slparcelauctions.backend.admin.audit.AdminAction}
 * audit row, and fires a user-side notification through {@link NotificationPublisher}.
 *
 * <p>Force-complete / force-fail of pending withdrawals reuse
 * {@link WalletService#recordWithdrawalSuccess} and
 * {@link WalletService#recordWithdrawalReversal} so the ledger is written
 * exactly the way a natural callback would have.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminWalletService {

    private final UserRepository userRepository;
    private final UserLedgerRepository ledgerRepository;
    private final TerminalCommandRepository terminalCommandRepository;
    private final WalletService walletService;
    private final WalletBroadcastPublisher walletBroadcastPublisher;
    private final AdminActionService adminActionService;
    private final NotificationPublisher notificationPublisher;
    private final Clock clock;

    /* ============================================================== */
    /* Read                                                            */
    /* ============================================================== */

    @Transactional(readOnly = true)
    public AdminWalletSnapshotDto snapshot(Long userId) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + userId));
        return toSnapshot(u);
    }

    @Transactional(readOnly = true)
    public PagedResponse<AdminLedgerRowDto> ledger(Long userId, Pageable pageable) {
        Page<UserLedgerEntry> page =
                ledgerRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        return PagedResponse.from(page.map(this::toLedgerRow));
    }

    /* ============================================================== */
    /* Mutations                                                       */
    /* ============================================================== */

    @Transactional
    public AdminWalletSnapshotDto adjust(Long userId, AdminWalletAdjustRequest req, Long adminId) {
        if (req.amount() == 0L) {
            throw new AdminWalletStateException("AMOUNT_ZERO",
                    "adjustment amount must be non-zero");
        }
        User u = userRepository.findByIdForUpdate(userId).orElseThrow();
        long newBalance = u.getBalanceLindens() + req.amount();
        if (newBalance < u.getReservedLindens() && !req.overrideReservationFloor()) {
            throw new AdminWalletStateException("RESERVATION_FLOOR",
                    "adjustment would push balance below reservation floor: "
                            + "newBalance=" + newBalance + ", reserved=" + u.getReservedLindens());
        }
        u.setBalanceLindens(newBalance);
        userRepository.save(u);
        UserLedgerEntry entry = ledgerRepository.save(UserLedgerEntry.builder()
                .userId(userId)
                .entryType(UserLedgerEntryType.ADJUSTMENT)
                .amount(req.amount())
                .balanceAfter(newBalance)
                .reservedAfter(u.getReservedLindens())
                .description(req.notes())
                .createdByAdminId(adminId)
                .build());
        walletBroadcastPublisher.publish(u,
                UserLedgerEntryType.ADJUSTMENT.name(), entry.getPublicId());
        adminActionService.record(adminId, AdminActionType.WALLET_ADJUST,
                AdminActionTargetType.USER, userId, req.notes(),
                Map.of("amount", req.amount(),
                        "overrideReservationFloor", req.overrideReservationFloor(),
                        "balanceAfter", newBalance));
        notificationPublisher.walletAdjusted(userId, req.amount(), req.notes());
        log.info("admin wallet adjust: userId={}, adminId={}, delta={}, balanceAfter={}",
                userId, adminId, req.amount(), newBalance);
        return toSnapshot(u);
    }

    @Transactional
    public AdminWalletSnapshotDto freeze(Long userId, AdminWalletNotesRequest req, Long adminId) {
        User u = userRepository.findByIdForUpdate(userId).orElseThrow();
        if (u.getWalletFrozenAt() != null) {
            throw new AdminWalletStateException("ALREADY_FROZEN",
                    "wallet is already frozen since " + u.getWalletFrozenAt());
        }
        u.setWalletFrozenAt(OffsetDateTime.now(clock));
        u.setWalletFrozenByAdminId(adminId);
        u.setWalletFrozenReason(req.notes());
        userRepository.save(u);
        adminActionService.record(adminId, AdminActionType.WALLET_FREEZE,
                AdminActionTargetType.USER, userId, req.notes(), Map.of());
        notificationPublisher.walletFrozen(userId, req.notes());
        log.info("admin wallet freeze: userId={}, adminId={}", userId, adminId);
        return toSnapshot(u);
    }

    @Transactional
    public AdminWalletSnapshotDto unfreeze(Long userId, AdminWalletNotesRequest req, Long adminId) {
        User u = userRepository.findByIdForUpdate(userId).orElseThrow();
        if (u.getWalletFrozenAt() == null) {
            throw new AdminWalletStateException("NOT_FROZEN",
                    "wallet is not currently frozen");
        }
        u.setWalletFrozenAt(null);
        u.setWalletFrozenByAdminId(null);
        u.setWalletFrozenReason(null);
        userRepository.save(u);
        adminActionService.record(adminId, AdminActionType.WALLET_UNFREEZE,
                AdminActionTargetType.USER, userId, req.notes(), Map.of());
        notificationPublisher.walletUnfrozen(userId, req.notes());
        log.info("admin wallet unfreeze: userId={}, adminId={}", userId, adminId);
        return toSnapshot(u);
    }

    @Transactional
    public AdminWalletSnapshotDto forgivePenalty(
            Long userId, AdminWalletForgivePenaltyRequest req, Long adminId) {
        User u = userRepository.findByIdForUpdate(userId).orElseThrow();
        if (req.amount() > u.getPenaltyBalanceOwed()) {
            throw new AdminWalletStateException("AMOUNT_EXCEEDS_OWED",
                    "forgive amount exceeds owed: amount=" + req.amount()
                            + ", owed=" + u.getPenaltyBalanceOwed());
        }
        long newOwed = u.getPenaltyBalanceOwed() - req.amount();
        u.setPenaltyBalanceOwed(newOwed);
        userRepository.save(u);
        adminActionService.record(adminId, AdminActionType.WALLET_FORGIVE_PENALTY,
                AdminActionTargetType.USER, userId, req.notes(),
                Map.of("amount", req.amount(), "remainingOwed", newOwed));
        notificationPublisher.walletPenaltyForgiven(userId, req.amount(), req.notes());
        log.info("admin wallet forgive penalty: userId={}, adminId={}, amount={}, remaining={}",
                userId, adminId, req.amount(), newOwed);
        return toSnapshot(u);
    }

    @Transactional
    public AdminWalletSnapshotDto resetDormancy(
            Long userId, AdminWalletNotesRequest req, Long adminId) {
        User u = userRepository.findByIdForUpdate(userId).orElseThrow();
        if (u.getWalletDormancyStartedAt() == null) {
            throw new AdminWalletStateException("NOT_IN_DORMANCY",
                    "wallet is not in dormancy state");
        }
        u.setWalletDormancyStartedAt(null);
        u.setWalletDormancyPhase(null);
        userRepository.save(u);
        adminActionService.record(adminId, AdminActionType.WALLET_RESET_DORMANCY,
                AdminActionTargetType.USER, userId, req.notes(), Map.of());
        notificationPublisher.walletDormancyReset(userId, req.notes());
        log.info("admin wallet reset dormancy: userId={}, adminId={}", userId, adminId);
        return toSnapshot(u);
    }

    @Transactional
    public AdminWalletSnapshotDto clearTerms(
            Long userId, AdminWalletNotesRequest req, Long adminId) {
        User u = userRepository.findByIdForUpdate(userId).orElseThrow();
        u.setWalletTermsAcceptedAt(null);
        u.setWalletTermsVersion(null);
        userRepository.save(u);
        adminActionService.record(adminId, AdminActionType.WALLET_CLEAR_TERMS,
                AdminActionTargetType.USER, userId, req.notes(), Map.of());
        notificationPublisher.walletTermsCleared(userId, req.notes());
        log.info("admin wallet clear terms: userId={}, adminId={}", userId, adminId);
        return toSnapshot(u);
    }

    @Transactional
    public AdminWalletSnapshotDto forceCompleteWithdrawal(
            Long userId, Long terminalCommandId, AdminWalletNotesRequest req, Long adminId) {
        TerminalCommand cmd = lockAndValidateCommand(userId, terminalCommandId);
        Long ledgerId = parseLedgerId(cmd);
        cmd.setStatus(TerminalCommandStatus.COMPLETED);
        cmd.setCompletedAt(OffsetDateTime.now(clock));
        cmd.setLastError(null);
        terminalCommandRepository.save(cmd);
        walletService.recordWithdrawalSuccess(ledgerId, "ADMIN-FORCE-" + cmd.getId());
        adminActionService.record(adminId, AdminActionType.WITHDRAWAL_FORCE_COMPLETE,
                AdminActionTargetType.USER, userId, req.notes(),
                Map.of("terminalCommandId", terminalCommandId,
                        "amount", cmd.getAmount(),
                        "ledgerEntryId", ledgerId));
        notificationPublisher.walletWithdrawalForceCompleted(
                userId, cmd.getAmount(), ledgerId, req.notes());
        log.info("admin force-complete withdrawal: userId={}, adminId={}, cmdId={}, amount={}",
                userId, adminId, terminalCommandId, cmd.getAmount());
        User u = userRepository.findById(userId).orElseThrow();
        return toSnapshot(u);
    }

    @Transactional
    public AdminWalletSnapshotDto forceFailWithdrawal(
            Long userId, Long terminalCommandId, AdminWalletNotesRequest req, Long adminId) {
        TerminalCommand cmd = lockAndValidateCommand(userId, terminalCommandId);
        Long ledgerId = parseLedgerId(cmd);
        String reason = "admin force-fail: " + req.notes();
        cmd.setStatus(TerminalCommandStatus.FAILED);
        cmd.setLastError(reason);
        cmd.setRequiresManualReview(true);
        terminalCommandRepository.save(cmd);
        walletService.recordWithdrawalReversal(ledgerId, reason);
        adminActionService.record(adminId, AdminActionType.WITHDRAWAL_FORCE_FAIL,
                AdminActionTargetType.USER, userId, req.notes(),
                Map.of("terminalCommandId", terminalCommandId,
                        "amount", cmd.getAmount(),
                        "ledgerEntryId", ledgerId));
        notificationPublisher.walletWithdrawalForceFailed(
                userId, cmd.getAmount(), ledgerId, req.notes());
        log.info("admin force-fail withdrawal: userId={}, adminId={}, cmdId={}, amount={}",
                userId, adminId, terminalCommandId, cmd.getAmount());
        User u = userRepository.findById(userId).orElseThrow();
        return toSnapshot(u);
    }

    /* ============================================================== */
    /* Internals                                                       */
    /* ============================================================== */

    private TerminalCommand lockAndValidateCommand(Long userId, Long terminalCommandId) {
        TerminalCommand cmd = terminalCommandRepository.findByIdForUpdate(terminalCommandId)
                .orElseThrow(() -> new AdminWalletStateException("COMMAND_NOT_FOUND",
                        "terminal command not found: " + terminalCommandId));
        // Verify the command belongs to this user (recipient_uuid match).
        User u = userRepository.findById(userId).orElseThrow();
        String slUuid = u.getSlAvatarUuid() == null ? null : u.getSlAvatarUuid().toString();
        if (slUuid == null || !slUuid.equals(cmd.getRecipientUuid())) {
            throw new AdminWalletStateException("COMMAND_USER_MISMATCH",
                    "terminal command " + terminalCommandId + " does not belong to user " + userId);
        }
        if (cmd.getStatus() != TerminalCommandStatus.QUEUED) {
            throw new AdminWalletStateException(
                    cmd.getStatus() == TerminalCommandStatus.IN_FLIGHT ? "BOT_PROCESSING" : "WITHDRAWAL_NOT_PENDING",
                    "withdrawal not in QUEUED state: " + cmd.getStatus());
        }
        return cmd;
    }

    private Long parseLedgerId(TerminalCommand cmd) {
        String key = cmd.getIdempotencyKey();
        if (key == null || !key.startsWith("WAL-")) {
            throw new IllegalStateException(
                    "wallet withdrawal command has unexpected idempotencyKey: " + key);
        }
        try {
            return Long.parseLong(key.substring(4));
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "wallet withdrawal command idempotencyKey not parseable: " + key);
        }
    }

    private AdminWalletSnapshotDto toSnapshot(User u) {
        List<TerminalCommand> pending = u.getSlAvatarUuid() == null
                ? List.of()
                : terminalCommandRepository.findPendingWalletWithdrawalsByRecipient(
                        u.getSlAvatarUuid().toString());
        List<AdminPendingWithdrawalDto> pendingDtos = pending.stream()
                .map(c -> new AdminPendingWithdrawalDto(
                        c.getId(), c.getAmount(), c.getRecipientUuid(),
                        c.getCreatedAt(), c.getDispatchedAt(),
                        c.getAttemptCount(), c.getStatus(),
                        c.getStatus() == TerminalCommandStatus.QUEUED))
                .toList();
        return new AdminWalletSnapshotDto(
                u.getPublicId(),
                u.getUsername(),
                u.getBalanceLindens(),
                u.getReservedLindens(),
                u.availableLindens(),
                u.getPenaltyBalanceOwed(),
                u.getWalletFrozenAt(),
                u.getWalletFrozenReason(),
                u.getWalletFrozenByAdminId(),
                u.getWalletDormancyStartedAt(),
                u.getWalletDormancyPhase(),
                u.getWalletTermsAcceptedAt(),
                u.getWalletTermsVersion(),
                pendingDtos);
    }

    private AdminLedgerRowDto toLedgerRow(UserLedgerEntry e) {
        return new AdminLedgerRowDto(
                e.getId(), e.getEntryType(),
                e.getAmount(), e.getBalanceAfter(), e.getReservedAfter(),
                e.getCreatedAt(),
                e.getDescription(), e.getRefType(), e.getRefId(),
                e.getCreatedByAdminId());
    }

    public static PagedResponse<AdminLedgerRowDto> emptyPage(Pageable pageable) {
        return PagedResponse.from(Page.empty(pageable == null ? PageRequest.of(0, 25) : pageable));
    }
}
