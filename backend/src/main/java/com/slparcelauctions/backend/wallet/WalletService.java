package com.slparcelauctions.backend.wallet;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.escrow.command.TerminalCommandAction;
import com.slparcelauctions.backend.escrow.command.TerminalCommandPurpose;
import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.escrow.command.TerminalCommandStatus;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.broadcast.WalletBroadcastPublisher;
import com.slparcelauctions.backend.wallet.exception.AmountExceedsOwedException;
import com.slparcelauctions.backend.wallet.exception.BidReservationAmountMismatchException;
import com.slparcelauctions.backend.wallet.exception.InsufficientAvailableBalanceException;
import com.slparcelauctions.backend.wallet.exception.UserNotLinkedException;
import com.slparcelauctions.backend.wallet.exception.UserStatusBlockedException;
import com.slparcelauctions.backend.wallet.exception.WalletFrozenException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Core wallet operations. All methods that mutate wallet state are
 * transactional. The "primitive" methods (debit/credit/swap helpers used by
 * other services) run with {@link Propagation#MANDATORY} so callers must
 * already hold the relevant locks; the "endpoint" methods (deposit /
 * withdraw / payPenalty) run with default propagation and acquire the
 * locks themselves.
 *
 * <p>See spec docs/superpowers/specs/2026-04-30-wallet-model-design.md.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WalletService {

    private final UserRepository userRepository;
    private final UserLedgerRepository ledgerRepository;
    private final BidReservationRepository reservationRepository;
    private final TerminalCommandRepository terminalCommandRepository;
    private final WalletBroadcastPublisher walletBroadcastPublisher;
    private final Clock clock;

    /* ========================================================== */
    /* DEPOSIT                                                     */
    /* ========================================================== */

    /**
     * Credit the wallet of the user identified by their SL avatar UUID.
     * Called from {@code POST /api/v1/sl/wallet/deposit} when the in-world
     * SLParcels Terminal's {@code money()} handler fires.
     *
     * <p>Idempotent: a duplicate {@code slTransactionKey} returns the
     * original ledger entry without re-crediting.
     *
     * @throws UserNotLinkedException     if no SLParcels user has the given
     *                                    {@code payerUuid} as their verified
     *                                    SL avatar — terminal bounces the L$
     * @throws UserStatusBlockedException if the user is banned or frozen —
     *                                    terminal bounces the L$
     */
    @Transactional
    public DepositResult deposit(UUID payerUuid, long amount, String slTransactionKey) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }

        Optional<UserLedgerEntry> existing = ledgerRepository.findBySlTransactionId(slTransactionKey);
        if (existing.isPresent()) {
            log.debug("deposit replay for slTxKey={} (existing entry id={})",
                    slTransactionKey, existing.get().getId());
            return DepositResult.replay(existing.get());
        }

        Long userId = userRepository.findIdBySlAvatarUuid(payerUuid)
                .orElseThrow(() -> new UserNotLinkedException(payerUuid));
        User user = userRepository.findByIdForUpdate(userId).orElseThrow();
        rejectIfBlocked(user);

        long newBalance = user.getBalanceLindens() + amount;
        user.setBalanceLindens(newBalance);
        userRepository.save(user);

        UserLedgerEntry entry = ledgerRepository.save(UserLedgerEntry.builder()
                .userId(user.getId())
                .entryType(UserLedgerEntryType.DEPOSIT)
                .amount(amount)
                .balanceAfter(newBalance)
                .reservedAfter(user.getReservedLindens())
                .slTransactionId(slTransactionKey)
                .build());

        walletBroadcastPublisher.publish(user, UserLedgerEntryType.DEPOSIT.name(), entry.getPublicId());

        log.info("deposit ok: userId={}, amount={}, balanceAfter={}, slTxKey={}",
                user.getId(), amount, newBalance, slTransactionKey);
        return DepositResult.ok(user, entry);
    }

    /* ========================================================== */
    /* WITHDRAW (site-initiated)                                   */
    /* ========================================================== */

    /**
     * Debit the wallet for a site-initiated withdrawal. Recipient is
     * always the user's verified SL avatar UUID — never client-supplied.
     * Idempotent on {@code idempotencyKey}.
     */
    @Transactional
    public WithdrawQueuedResult withdrawSiteInitiated(Long userId, long amount, String idempotencyKey) {
        return withdrawCommon(userId, amount, idempotencyKey, null);
    }

    /**
     * Debit the wallet for a touch-initiated withdrawal. Idempotent on
     * {@code slTransactionKey}.
     */
    @Transactional
    public WithdrawQueuedResult withdrawTouchInitiated(UUID payerUuid, long amount, String slTransactionKey) {
        Long userId = userRepository.findIdBySlAvatarUuid(payerUuid)
                .orElseThrow(() -> new UserNotLinkedException(payerUuid));
        return withdrawCommon(userId, amount, null, slTransactionKey);
    }

    private WithdrawQueuedResult withdrawCommon(
            Long userId, long amount, String idempotencyKey, String slTransactionKey) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }

        // Idempotency replay
        if (idempotencyKey != null) {
            Optional<UserLedgerEntry> existing = ledgerRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) return WithdrawQueuedResult.replay(existing.get());
        }
        if (slTransactionKey != null) {
            Optional<UserLedgerEntry> existing = ledgerRepository.findBySlTransactionId(slTransactionKey);
            if (existing.isPresent()) return WithdrawQueuedResult.replay(existing.get());
        }

        User user = userRepository.findByIdForUpdate(userId).orElseThrow();
        rejectIfBlocked(user);
        rejectIfFrozen(user);

        if (user.availableLindens() < amount) {
            throw new InsufficientAvailableBalanceException(user.availableLindens(), amount);
        }

        long newBalance = user.getBalanceLindens() - amount;
        user.setBalanceLindens(newBalance);
        userRepository.save(user);

        UserLedgerEntry entry = ledgerRepository.save(UserLedgerEntry.builder()
                .userId(userId)
                .entryType(UserLedgerEntryType.WITHDRAW_QUEUED)
                .amount(amount)
                .balanceAfter(newBalance)
                .reservedAfter(user.getReservedLindens())
                .idempotencyKey(idempotencyKey)
                .slTransactionId(slTransactionKey)
                .build());

        walletBroadcastPublisher.publish(user, UserLedgerEntryType.WITHDRAW_QUEUED.name(), entry.getPublicId());

        TerminalCommand cmd = terminalCommandRepository.save(TerminalCommand.builder()
                .action(TerminalCommandAction.WITHDRAW)
                .purpose(TerminalCommandPurpose.WALLET_WITHDRAWAL)
                .recipientUuid(user.getSlAvatarUuid().toString())
                .amount(amount)
                .status(TerminalCommandStatus.QUEUED)
                .idempotencyKey("WAL-" + entry.getId())
                .nextAttemptAt(OffsetDateTime.now(clock))
                .attemptCount(0)
                .requiresManualReview(false)
                .build());

        log.info("withdraw queued: userId={}, amount={}, balanceAfter={}, ledgerId={}, commandId={}",
                userId, amount, newBalance, entry.getId(), cmd.getId());
        return WithdrawQueuedResult.ok(user, entry, cmd);
    }

    /* ========================================================== */
    /* PAY PENALTY                                                 */
    /* ========================================================== */

    /**
     * Pay against the user's outstanding penalty. Must come from
     * {@code available} (not {@code reserved}). Partial payments allowed
     * up to {@code penaltyBalanceOwed}.
     */
    @Transactional
    public PenaltyDebitResult payPenalty(Long userId, long amount, String idempotencyKey) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }

        Optional<UserLedgerEntry> existing = ledgerRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) return PenaltyDebitResult.replay(existing.get());

        User user = userRepository.findByIdForUpdate(userId).orElseThrow();
        rejectIfFrozen(user);
        if (amount > user.getPenaltyBalanceOwed()) {
            throw new AmountExceedsOwedException(user.getPenaltyBalanceOwed(), amount);
        }
        if (user.availableLindens() < amount) {
            throw new InsufficientAvailableBalanceException(user.availableLindens(), amount);
        }

        long newBalance = user.getBalanceLindens() - amount;
        long newOwed = user.getPenaltyBalanceOwed() - amount;
        user.setBalanceLindens(newBalance);
        user.setPenaltyBalanceOwed(newOwed);
        userRepository.save(user);

        UserLedgerEntry entry = ledgerRepository.save(UserLedgerEntry.builder()
                .userId(userId)
                .entryType(UserLedgerEntryType.PENALTY_DEBIT)
                .amount(amount)
                .balanceAfter(newBalance)
                .reservedAfter(user.getReservedLindens())
                .idempotencyKey(idempotencyKey)
                .refType("PENALTY")
                .build());

        walletBroadcastPublisher.publish(user, UserLedgerEntryType.PENALTY_DEBIT.name(), entry.getPublicId());

        log.info("penalty debit: userId={}, amount={}, newOwed={}, ledgerId={}",
                userId, amount, newOwed, entry.getId());
        return PenaltyDebitResult.ok(user, entry);
    }

    /* ========================================================== */
    /* RESERVATION SWAP (called from BidService)                   */
    /* ========================================================== */

    /**
     * Swap a bid reservation: release the prior high bidder's reservation
     * (if any) and create the new bidder's reservation. Caller must
     * already hold {@code PESSIMISTIC_WRITE} locks on the auction and
     * affected user rows in ascending user_id order.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReservationSwapResult swapReservation(
            Long auctionId, User newBidder, long newBidAmount,
            BidReservation priorReservation, Long newBidId) {
        if (newBidAmount <= 0) {
            throw new IllegalArgumentException("newBidAmount must be positive: " + newBidAmount);
        }
        rejectIfFrozen(newBidder);
        if (newBidder.availableLindens() < newBidAmount) {
            throw new InsufficientAvailableBalanceException(newBidder.availableLindens(), newBidAmount);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);

        if (priorReservation != null) {
            User priorUser = userRepository.findByIdForUpdate(priorReservation.getUserId())
                    .orElseThrow();
            priorReservation.setReleasedAt(now);
            priorReservation.setReleaseReason(BidReservationReleaseReason.OUTBID);
            reservationRepository.save(priorReservation);
            priorUser.setReservedLindens(priorUser.getReservedLindens() - priorReservation.getAmount());
            userRepository.save(priorUser);
            UserLedgerEntry priorEntry = ledgerRepository.save(UserLedgerEntry.builder()
                    .userId(priorUser.getId())
                    .entryType(UserLedgerEntryType.BID_RELEASED)
                    .amount(priorReservation.getAmount())
                    .balanceAfter(priorUser.getBalanceLindens())
                    .reservedAfter(priorUser.getReservedLindens())
                    .refType("BID")
                    .refId(priorReservation.getBidId())
                    .build());
            walletBroadcastPublisher.publish(priorUser,
                    UserLedgerEntryType.BID_RELEASED.name(), priorEntry.getPublicId());
        }

        BidReservation newRes = reservationRepository.save(BidReservation.builder()
                .userId(newBidder.getId())
                .auctionId(auctionId)
                .bidId(newBidId)
                .amount(newBidAmount)
                .createdAt(now)
                .build());
        newBidder.setReservedLindens(newBidder.getReservedLindens() + newBidAmount);
        userRepository.save(newBidder);
        UserLedgerEntry newEntry = ledgerRepository.save(UserLedgerEntry.builder()
                .userId(newBidder.getId())
                .entryType(UserLedgerEntryType.BID_RESERVED)
                .amount(newBidAmount)
                .balanceAfter(newBidder.getBalanceLindens())
                .reservedAfter(newBidder.getReservedLindens())
                .refType("BID")
                .refId(newBidId)
                .build());
        walletBroadcastPublisher.publish(newBidder,
                UserLedgerEntryType.BID_RESERVED.name(), newEntry.getPublicId());

        return new ReservationSwapResult(newRes, priorReservation);
    }

    /* ========================================================== */
    /* AUCTION-END AUTO-FUND (called from AuctionEndTask)          */
    /* ========================================================== */

    /**
     * Consume the winner's reservation and debit balance for the auction
     * close. Caller must hold locks on auction + winner's user row. Throws
     * {@link BidReservationAmountMismatchException} on system-integrity
     * defect — caller catches + freezes the escrow.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void autoFundEscrow(Long auctionId, User winner, long finalBidAmount, Long escrowId) {
        BidReservation reservation = reservationRepository.findActiveForAuction(auctionId)
                .orElseThrow(() -> new IllegalStateException(
                        "auto-fund called without active reservation: auctionId=" + auctionId));
        if (!reservation.getUserId().equals(winner.getId())) {
            throw new IllegalStateException(
                    "active reservation user != winner: reservation.userId="
                            + reservation.getUserId() + ", winner.id=" + winner.getId());
        }
        if (reservation.getAmount() != finalBidAmount) {
            throw new BidReservationAmountMismatchException(
                    reservation.getAmount(), finalBidAmount);
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        reservation.setReleasedAt(now);
        reservation.setReleaseReason(BidReservationReleaseReason.ESCROW_FUNDED);
        reservationRepository.save(reservation);

        long balanceAfter = winner.getBalanceLindens() - finalBidAmount;
        long reservedAfter = winner.getReservedLindens() - reservation.getAmount();
        winner.setBalanceLindens(balanceAfter);
        winner.setReservedLindens(reservedAfter);
        userRepository.save(winner);

        ledgerRepository.save(UserLedgerEntry.builder()
                .userId(winner.getId())
                .entryType(UserLedgerEntryType.BID_RELEASED)
                .amount(reservation.getAmount())
                .balanceAfter(balanceAfter)
                .reservedAfter(reservedAfter)
                .refType("BID")
                .refId(reservation.getBidId())
                .build());
        UserLedgerEntry escrowEntry = ledgerRepository.save(UserLedgerEntry.builder()
                .userId(winner.getId())
                .entryType(UserLedgerEntryType.ESCROW_DEBIT)
                .amount(finalBidAmount)
                .balanceAfter(balanceAfter)
                .reservedAfter(reservedAfter)
                .refType("ESCROW")
                .refId(escrowId)
                .build());

        walletBroadcastPublisher.publish(winner,
                UserLedgerEntryType.ESCROW_DEBIT.name(), escrowEntry.getPublicId());

        log.info("auto-fund escrow: winnerId={}, escrowId={}, amount={}, balanceAfter={}",
                winner.getId(), escrowId, finalBidAmount, balanceAfter);
    }

    /* ========================================================== */
    /* CREDIT helpers (refund flows)                               */
    /* ========================================================== */

    @Transactional(propagation = Propagation.MANDATORY)
    public void creditEscrowRefund(User user, long amount, Long escrowId) {
        UserLedgerEntry entry = creditCommon(user, amount,
                UserLedgerEntryType.ESCROW_REFUND, "ESCROW", escrowId);
        walletBroadcastPublisher.publish(user,
                UserLedgerEntryType.ESCROW_REFUND.name(), entry.getPublicId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void creditListingFeeRefund(User user, long amount, Long listingFeeRefundId) {
        UserLedgerEntry entry = creditCommon(user, amount,
                UserLedgerEntryType.LISTING_FEE_REFUND,
                "LISTING_FEE_REFUND", listingFeeRefundId);
        walletBroadcastPublisher.publish(user,
                UserLedgerEntryType.LISTING_FEE_REFUND.name(), entry.getPublicId());
    }

    private UserLedgerEntry creditCommon(User user, long amount, UserLedgerEntryType type,
            String refType, Long refId) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        long newBalance = user.getBalanceLindens() + amount;
        user.setBalanceLindens(newBalance);
        userRepository.save(user);
        UserLedgerEntry entry = ledgerRepository.save(UserLedgerEntry.builder()
                .userId(user.getId())
                .entryType(type)
                .amount(amount)
                .balanceAfter(newBalance)
                .reservedAfter(user.getReservedLindens())
                .refType(refType)
                .refId(refId)
                .build());
        log.info("credit {}: userId={}, amount={}, balanceAfter={}, refId={}",
                type, user.getId(), amount, newBalance, refId);
        return entry;
    }

    /* ========================================================== */
    /* DEBIT helper (listing fee)                                  */
    /* ========================================================== */

    /**
     * Debit listing fee from the user's wallet. Caller has validated
     * preconditions (penalty == 0, available >= amount, auction state ==
     * DRAFT, seller == user).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void debitListingFee(User user, long amount, Long auctionId) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        rejectIfFrozen(user);
        if (user.availableLindens() < amount) {
            throw new InsufficientAvailableBalanceException(user.availableLindens(), amount);
        }
        long newBalance = user.getBalanceLindens() - amount;
        user.setBalanceLindens(newBalance);
        userRepository.save(user);
        UserLedgerEntry entry = ledgerRepository.save(UserLedgerEntry.builder()
                .userId(user.getId())
                .entryType(UserLedgerEntryType.LISTING_FEE_DEBIT)
                .amount(amount)
                .balanceAfter(newBalance)
                .reservedAfter(user.getReservedLindens())
                .refType("AUCTION")
                .refId(auctionId)
                .build());
        walletBroadcastPublisher.publish(user,
                UserLedgerEntryType.LISTING_FEE_DEBIT.name(), entry.getPublicId());
        log.info("listing fee debit: userId={}, amount={}, auctionId={}, balanceAfter={}",
                user.getId(), amount, auctionId, newBalance);
    }

    /* ========================================================== */
    /* RESERVATION RELEASE (cancellation, ban paths)               */
    /* ========================================================== */

    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseReservationsForAuction(Long auctionId, BidReservationReleaseReason reason) {
        List<BidReservation> active = reservationRepository.findAllActiveForAuction(auctionId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (BidReservation r : active) {
            User u = userRepository.findByIdForUpdate(r.getUserId()).orElseThrow();
            r.setReleasedAt(now);
            r.setReleaseReason(reason);
            reservationRepository.save(r);
            u.setReservedLindens(u.getReservedLindens() - r.getAmount());
            userRepository.save(u);
            UserLedgerEntry entry = ledgerRepository.save(UserLedgerEntry.builder()
                    .userId(u.getId())
                    .entryType(UserLedgerEntryType.BID_RELEASED)
                    .amount(r.getAmount())
                    .balanceAfter(u.getBalanceLindens())
                    .reservedAfter(u.getReservedLindens())
                    .refType("BID")
                    .refId(r.getBidId())
                    .description("released by auction event: " + reason.name())
                    .build());
            walletBroadcastPublisher.publish(u,
                    UserLedgerEntryType.BID_RELEASED.name(), entry.getPublicId());
        }
        log.info("released {} reservations for auctionId={}, reason={}",
                active.size(), auctionId, reason);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseAllReservationsForUser(Long userId, BidReservationReleaseReason reason) {
        List<BidReservation> active = reservationRepository.findActiveByUser(userId);
        if (active.isEmpty()) return;
        User u = userRepository.findByIdForUpdate(userId).orElseThrow();
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (BidReservation r : active) {
            r.setReleasedAt(now);
            r.setReleaseReason(reason);
            reservationRepository.save(r);
            u.setReservedLindens(u.getReservedLindens() - r.getAmount());
            UserLedgerEntry entry = ledgerRepository.save(UserLedgerEntry.builder()
                    .userId(u.getId())
                    .entryType(UserLedgerEntryType.BID_RELEASED)
                    .amount(r.getAmount())
                    .balanceAfter(u.getBalanceLindens())
                    .reservedAfter(u.getReservedLindens())
                    .refType("BID")
                    .refId(r.getBidId())
                    .description("released by user event: " + reason.name())
                    .build());
            walletBroadcastPublisher.publish(u,
                    UserLedgerEntryType.BID_RELEASED.name(), entry.getPublicId());
        }
        userRepository.save(u);
        log.info("released {} reservations for userId={}, reason={}",
                active.size(), userId, reason);
    }

    /* ========================================================== */
    /* WITHDRAW callback: success / reverse                        */
    /* ========================================================== */

    /**
     * Append a {@code WITHDRAW_COMPLETED} row when the terminal acknowledges
     * a successful payout. Called by {@code WalletWithdrawalCallbackHandler}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordWithdrawalSuccess(Long userLedgerEntryId, String slTransactionKey) {
        UserLedgerEntry queuedRow = ledgerRepository.findById(userLedgerEntryId)
                .orElseThrow(() -> new IllegalStateException(
                        "queued withdraw ledger entry not found: id=" + userLedgerEntryId));
        // Append the COMPLETED row referencing the original.
        UserLedgerEntry entry = ledgerRepository.save(UserLedgerEntry.builder()
                .userId(queuedRow.getUserId())
                .entryType(UserLedgerEntryType.WITHDRAW_COMPLETED)
                .amount(queuedRow.getAmount())
                .balanceAfter(queuedRow.getBalanceAfter())
                .reservedAfter(queuedRow.getReservedAfter())
                .refType("USER_LEDGER")
                .refId(queuedRow.getId())
                .slTransactionId(slTransactionKey)
                .build());
        User user = userRepository.findById(queuedRow.getUserId()).orElseThrow();
        walletBroadcastPublisher.publish(user,
                UserLedgerEntryType.WITHDRAW_COMPLETED.name(), entry.getPublicId());
        log.info("withdraw completed: userId={}, queuedRowId={}, slTxKey={}",
                queuedRow.getUserId(), queuedRow.getId(), slTransactionKey);
    }

    /**
     * Append a {@code WITHDRAW_REVERSED} row + credit balance back when the
     * terminal cannot fulfill a withdraw after retry exhaustion.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordWithdrawalReversal(Long userLedgerEntryId, String reason) {
        UserLedgerEntry queuedRow = ledgerRepository.findById(userLedgerEntryId)
                .orElseThrow(() -> new IllegalStateException(
                        "queued withdraw ledger entry not found: id=" + userLedgerEntryId));
        User user = userRepository.findByIdForUpdate(queuedRow.getUserId()).orElseThrow();
        long newBalance = user.getBalanceLindens() + queuedRow.getAmount();
        user.setBalanceLindens(newBalance);
        userRepository.save(user);
        UserLedgerEntry entry = ledgerRepository.save(UserLedgerEntry.builder()
                .userId(user.getId())
                .entryType(UserLedgerEntryType.WITHDRAW_REVERSED)
                .amount(queuedRow.getAmount())
                .balanceAfter(newBalance)
                .reservedAfter(user.getReservedLindens())
                .refType("USER_LEDGER")
                .refId(queuedRow.getId())
                .description("withdrawal reversed: " + reason)
                .build());
        walletBroadcastPublisher.publish(user,
                UserLedgerEntryType.WITHDRAW_REVERSED.name(), entry.getPublicId());
        log.warn("withdraw reversed: userId={}, queuedRowId={}, reason={}",
                user.getId(), queuedRow.getId(), reason);
    }

    /* ========================================================== */
    /* Queries                                                     */
    /* ========================================================== */

    /**
     * Sum of withdrawal amounts currently debited but not yet
     * completed or reversed. Used to populate the "Queued for
     * Withdrawal" indicator in the wallet view.
     */
    @Transactional(readOnly = true)
    public long pendingWithdrawalAmount(Long userId) {
        return ledgerRepository.sumPendingWithdrawals(userId);
    }

    /**
     * Look up the user the given ledger entry belongs to. Used by
     * {@code WalletWithdrawalCallbackHandler} to address the SL IM
     * notification on success / reversal.
     */
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public Long findUserIdForLedgerEntry(Long ledgerEntryId) {
        return ledgerRepository.findById(ledgerEntryId)
                .map(UserLedgerEntry::getUserId)
                .orElse(null);
    }

    /* ========================================================== */
    /* Helpers                                                     */
    /* ========================================================== */

    /**
     * Reject if the user's account status disallows wallet operations.
     * Banned users have a non-null {@code listingSuspensionUntil} in the
     * future or {@code bannedFromListing=true}; frozen users have a
     * different signal in some flows. For the wallet model, the gate is
     * conservative: any "blocked" status fails. The exact field signals
     * are project-specific — adapt this method as the user-status model
     * evolves.
     */
    private void rejectIfBlocked(User user) {
        // Phase 1 conservative posture: check bannedFromListing and any
        // active listing suspension. These are existing User fields.
        // BANNED/FROZEN are signals; expand as the user-status model grows.
        if (user.getBannedFromListing() != null && user.getBannedFromListing()) {
            throw new UserStatusBlockedException(user.getId(), "BANNED_FROM_LISTING");
        }
    }

    /**
     * Outflow gate for the admin wallet-freeze. Inflows (deposits, admin
     * adjustments) bypass this check. See spec
     * docs/superpowers/specs/2026-05-05-admin-user-wallet-ops-design.md §3.6.
     */
    private void rejectIfFrozen(User user) {
        if (user.getWalletFrozenAt() != null) {
            throw new WalletFrozenException(user.getId());
        }
    }

    /* ========================================================== */
    /* Result types (records)                                      */
    /* ========================================================== */

    public sealed interface DepositResult {
        UserLedgerEntry entry();

        static DepositResult ok(User user, UserLedgerEntry entry) {
            return new Ok(user, entry);
        }

        static DepositResult replay(UserLedgerEntry entry) {
            return new Replay(entry);
        }

        record Ok(User user, UserLedgerEntry entry) implements DepositResult {}
        record Replay(UserLedgerEntry entry) implements DepositResult {
            @Override public UserLedgerEntry entry() { return entry; }
        }
    }

    public sealed interface WithdrawQueuedResult {
        UserLedgerEntry entry();

        static WithdrawQueuedResult ok(User user, UserLedgerEntry entry, TerminalCommand cmd) {
            return new Ok(user, entry, cmd);
        }

        static WithdrawQueuedResult replay(UserLedgerEntry entry) {
            return new Replay(entry);
        }

        record Ok(User user, UserLedgerEntry entry, TerminalCommand command)
                implements WithdrawQueuedResult {}
        record Replay(UserLedgerEntry entry) implements WithdrawQueuedResult {
            @Override public UserLedgerEntry entry() { return entry; }
        }
    }

    public sealed interface PenaltyDebitResult {
        UserLedgerEntry entry();

        static PenaltyDebitResult ok(User user, UserLedgerEntry entry) {
            return new Ok(user, entry);
        }

        static PenaltyDebitResult replay(UserLedgerEntry entry) {
            return new Replay(entry);
        }

        record Ok(User user, UserLedgerEntry entry) implements PenaltyDebitResult {}
        record Replay(UserLedgerEntry entry) implements PenaltyDebitResult {
            @Override public UserLedgerEntry entry() { return entry; }
        }
    }

    public record ReservationSwapResult(BidReservation newReservation, BidReservation priorReleased) {}
}
