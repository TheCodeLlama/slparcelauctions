package com.slparcelauctions.backend.sl;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.escrow.EscrowTransaction;
import com.slparcelauctions.backend.escrow.EscrowTransactionRepository;
import com.slparcelauctions.backend.escrow.EscrowTransactionStatus;
import com.slparcelauctions.backend.escrow.EscrowTransactionType;
import com.slparcelauctions.backend.sl.dto.PenaltyLookupResponse;
import com.slparcelauctions.backend.sl.dto.PenaltyPaymentRequest;
import com.slparcelauctions.backend.sl.dto.PenaltyPaymentResponse;
import com.slparcelauctions.backend.sl.exception.PenaltyOverpaymentException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Domain service backing the SL terminal penalty endpoints (Epic 08
 * sub-spec 2 §7.5 / §7.6). Two operations:
 *
 * <ul>
 *   <li>{@link #lookup(UUID)} — read-only debt query. 404 if the avatar
 *       is unknown OR the user owes nothing.</li>
 *   <li>{@link #pay(PenaltyPaymentRequest)} — apply a partial-or-full
 *       payment. Idempotent on {@code slTransactionId}: a benign retry
 *       returns the current balance without writing a second ledger
 *       row. The non-idempotent path acquires a {@code PESSIMISTIC_WRITE}
 *       lock on the {@code User} row before reading the outstanding
 *       balance so two concurrent terminal sessions on the same seller
 *       serialise on the database side.</li>
 * </ul>
 *
 * <p>Idempotency lookup intentionally happens BEFORE the pessimistic
 * lock — a replay of an already-applied transaction must short-circuit
 * without competing for the write lock, otherwise two retried payments
 * for the same {@code slTransactionId} could each block waiting for the
 * other. The trade-off is a tiny race window where two non-replay
 * payments could see "no prior row" and both proceed to the lock; that
 * is exactly the case the lock + balance check exists to handle, and
 * the second writer either succeeds (if its amount fits the now-reduced
 * balance) or hits {@link PenaltyOverpaymentException}.
 *
 * <p>Ledger row shape mirrors the existing {@code AUCTION_ESCROW_COMMISSION}
 * convention: {@code payer} is the locked {@link User}, {@code payee} is
 * {@code null} (the platform side has no User entity), and the type is
 * {@link EscrowTransactionType#LISTING_PENALTY_PAYMENT}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PenaltyTerminalService {

    private final UserRepository userRepo;
    private final EscrowTransactionRepository ledgerRepo;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PenaltyLookupResponse lookup(UUID slAvatarUuid) {
        User u = userRepo.findBySlAvatarUuid(slAvatarUuid)
                .orElseThrow(() -> new UserNotFoundException(
                        "No SLPA user found for avatar " + slAvatarUuid));

        long balance = currentBalance(u);
        if (balance == 0L) {
            // Spec §7.5: zero-balance lookups are 404 so the terminal can
            // render a single "no debt" branch without inspecting the
            // numeric body. Distinguishing "unknown avatar" from "user
            // exists but owes nothing" would only be useful to an
            // attacker probing the user table.
            throw new UserNotFoundException(
                    "No outstanding penalty balance for avatar " + slAvatarUuid);
        }
        return new PenaltyLookupResponse(u.getId(), u.getDisplayName(), balance);
    }

    @Transactional
    public PenaltyPaymentResponse pay(PenaltyPaymentRequest req) {
        // Resolve avatar UUID → user id via a projection query so the
        // full User entity is not hydrated into the persistence context
        // before the lock is acquired. Hibernate's session cache would
        // otherwise return the pre-lock entity instance for the
        // subsequent findByIdForUpdate call with stale field values —
        // silently defeating the lock for the read path. See
        // UserRepository.findIdBySlAvatarUuid javadoc.
        Long userId = userRepo.findIdBySlAvatarUuid(req.slAvatarUuid())
                .orElseThrow(() -> new UserNotFoundException(
                        "No SLPA user found for avatar " + req.slAvatarUuid()));

        // Idempotency check happens BEFORE the pessimistic lock so a
        // benign terminal-side retry never competes for a write lock
        // (and never double-debits). Type-scoped on
        // LISTING_PENALTY_PAYMENT so a hypothetical slTransactionId
        // collision with another transaction kind cannot mask a real
        // payment.
        Optional<EscrowTransaction> existing = ledgerRepo
                .findFirstBySlTransactionIdAndType(
                        req.slTransactionId(),
                        EscrowTransactionType.LISTING_PENALTY_PAYMENT);
        if (existing.isPresent()) {
            // Replay path — no need for the lock. Re-read the balance
            // off a fresh entity (not cached) so the response reflects
            // any committed concurrent payment from a different
            // slTransactionId.
            User snapshot = userRepo.findById(userId)
                    .orElseThrow(() -> new UserNotFoundException(userId));
            log.info("Idempotent replay of penalty payment slTxn={} user={} terminal={}",
                    req.slTransactionId(), userId, req.terminalId());
            return new PenaltyPaymentResponse(currentBalance(snapshot));
        }

        // Acquire the write lock and re-read the balance under the lock
        // so two concurrent non-replay payments on the same user
        // serialise. The second writer either commits a smaller-or-fits
        // amount or hits the overpayment guard below.
        User locked = userRepo.findByIdForUpdate(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        long balance = currentBalance(locked);
        if (req.amount() > balance) {
            throw new PenaltyOverpaymentException(req.amount(), balance);
        }

        long newBalance = balance - req.amount();
        locked.setPenaltyBalanceOwed(newBalance);
        userRepo.save(locked);

        OffsetDateTime now = OffsetDateTime.now(clock);
        ledgerRepo.save(EscrowTransaction.builder()
                .type(EscrowTransactionType.LISTING_PENALTY_PAYMENT)
                .status(EscrowTransactionStatus.COMPLETED)
                .amount(req.amount())
                .payer(locked)
                .payee(null)
                .slTransactionId(req.slTransactionId())
                .terminalId(req.terminalId())
                .completedAt(now)
                .build());

        log.info("Penalty payment recorded: user={} amount=L${} remaining=L${} terminal={} slTxn={}",
                locked.getId(), req.amount(), newBalance, req.terminalId(), req.slTransactionId());

        return new PenaltyPaymentResponse(newBalance);
    }

    private static long currentBalance(User u) {
        // The column carries a NOT NULL DEFAULT 0 on the entity, but
        // legacy rows or in-memory test fixtures may still expose a null
        // — coerce defensively rather than NPE on a hot terminal call.
        Long balance = u.getPenaltyBalanceOwed();
        return balance == null ? 0L : balance;
    }
}
