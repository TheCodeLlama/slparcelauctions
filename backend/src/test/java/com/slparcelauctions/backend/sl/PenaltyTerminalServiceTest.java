package com.slparcelauctions.backend.sl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.slparcelauctions.backend.escrow.EscrowTransaction;
import com.slparcelauctions.backend.escrow.EscrowTransactionRepository;
import com.slparcelauctions.backend.escrow.EscrowTransactionStatus;
import com.slparcelauctions.backend.escrow.EscrowTransactionType;
import com.slparcelauctions.backend.notification.ws.AccountStateBroadcaster;
import com.slparcelauctions.backend.sl.dto.PenaltyLookupResponse;
import com.slparcelauctions.backend.sl.dto.PenaltyPaymentRequest;
import com.slparcelauctions.backend.sl.dto.PenaltyPaymentResponse;
import com.slparcelauctions.backend.sl.exception.PenaltyOverpaymentException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserNotFoundException;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * Pure service-layer pins for {@link PenaltyTerminalService}. Mocks
 * {@link UserRepository} + {@link EscrowTransactionRepository} so the
 * idempotency / overpayment / pessimistic-lock logic is exercised
 * without spinning up Spring. Concurrent serialisation under a real
 * row-lock is covered by the integration test
 * {@code PenaltyTerminalServiceConcurrencyIntegrationTest}.
 */
class PenaltyTerminalServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(
            2026, 4, 25, 18, 0, 0, 0, ZoneOffset.UTC);
    private static final Clock FIXED = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
    private static final UUID AVATAR = UUID.fromString("a1b2c3d4-a1b2-c3d4-e5f6-000000000123");
    private static final String SL_TXN = "sl-txn-pen-1";
    private static final String TERMINAL = "terminal-7";

    private UserRepository userRepo;
    private EscrowTransactionRepository ledgerRepo;
    private AccountStateBroadcaster accountBroadcaster;
    private PenaltyTerminalService service;

    @BeforeEach
    void setup() {
        userRepo = mock(UserRepository.class);
        ledgerRepo = mock(EscrowTransactionRepository.class);
        accountBroadcaster = mock(AccountStateBroadcaster.class);
        service = new PenaltyTerminalService(userRepo, ledgerRepo, accountBroadcaster, FIXED);
    }

    private static User userWithBalance(Long id, String displayName, Long balance) {
        return User.builder()
                .id(id)
                .email("seller-" + id + "@example.test")
                .passwordHash("x")
                .displayName(displayName)
                .slAvatarUuid(AVATAR)
                .penaltyBalanceOwed(balance)
                .build();
    }

    // -----------------------------------------------------------------
    // Lookup
    // -----------------------------------------------------------------

    @Test
    void lookup_returnsBalance_whenUserOwes() {
        User u = userWithBalance(7L, "Alice", 1000L);
        when(userRepo.findBySlAvatarUuid(AVATAR)).thenReturn(Optional.of(u));

        PenaltyLookupResponse resp = service.lookup(AVATAR);

        assertThat(resp.userId()).isEqualTo(7L);
        assertThat(resp.displayName()).isEqualTo("Alice");
        assertThat(resp.penaltyBalanceOwed()).isEqualTo(1000L);
    }

    @Test
    void lookup_throwsUserNotFound_whenAvatarUnknown() {
        when(userRepo.findBySlAvatarUuid(AVATAR)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.lookup(AVATAR))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void lookup_throwsUserNotFound_whenBalanceIsZero() {
        User u = userWithBalance(7L, "Alice", 0L);
        when(userRepo.findBySlAvatarUuid(AVATAR)).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> service.lookup(AVATAR))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void lookup_treatsNullBalanceAsZero() {
        User u = userWithBalance(7L, "Alice", null);
        when(userRepo.findBySlAvatarUuid(AVATAR)).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> service.lookup(AVATAR))
                .isInstanceOf(UserNotFoundException.class);
    }

    // -----------------------------------------------------------------
    // Payment — happy paths
    // -----------------------------------------------------------------

    @Test
    void pay_partialClear_decrementsBalanceAndWritesLedgerRow() {
        User u = userWithBalance(7L, "Alice", 1000L);
        when(userRepo.findIdBySlAvatarUuid(AVATAR)).thenReturn(Optional.of(7L));
        when(ledgerRepo.findFirstBySlTransactionIdAndType(
                SL_TXN, EscrowTransactionType.LISTING_PENALTY_PAYMENT))
                .thenReturn(Optional.empty());
        when(userRepo.findByIdForUpdate(7L)).thenReturn(Optional.of(u));
        when(userRepo.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        PenaltyPaymentResponse resp = service.pay(
                new PenaltyPaymentRequest(AVATAR, SL_TXN, 600L, TERMINAL));

        assertThat(resp.remainingBalance()).isEqualTo(400L);
        assertThat(u.getPenaltyBalanceOwed()).isEqualTo(400L);

        ArgumentCaptor<EscrowTransaction> tx = ArgumentCaptor.forClass(EscrowTransaction.class);
        verify(ledgerRepo).save(tx.capture());
        EscrowTransaction saved = tx.getValue();
        assertThat(saved.getType()).isEqualTo(EscrowTransactionType.LISTING_PENALTY_PAYMENT);
        assertThat(saved.getStatus()).isEqualTo(EscrowTransactionStatus.COMPLETED);
        assertThat(saved.getAmount()).isEqualTo(600L);
        assertThat(saved.getPayer()).isSameAs(u);
        assertThat(saved.getPayee()).isNull();
        assertThat(saved.getSlTransactionId()).isEqualTo(SL_TXN);
        assertThat(saved.getTerminalId()).isEqualTo(TERMINAL);
        assertThat(saved.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    void pay_fullClear_zeroesBalance() {
        User u = userWithBalance(7L, "Alice", 1000L);
        when(userRepo.findIdBySlAvatarUuid(AVATAR)).thenReturn(Optional.of(7L));
        when(ledgerRepo.findFirstBySlTransactionIdAndType(
                SL_TXN, EscrowTransactionType.LISTING_PENALTY_PAYMENT))
                .thenReturn(Optional.empty());
        when(userRepo.findByIdForUpdate(7L)).thenReturn(Optional.of(u));
        when(userRepo.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        PenaltyPaymentResponse resp = service.pay(
                new PenaltyPaymentRequest(AVATAR, SL_TXN, 1000L, TERMINAL));

        assertThat(resp.remainingBalance()).isEqualTo(0L);
        assertThat(u.getPenaltyBalanceOwed()).isEqualTo(0L);
        verify(ledgerRepo).save(any(EscrowTransaction.class));
    }

    // -----------------------------------------------------------------
    // Payment — idempotency
    // -----------------------------------------------------------------

    @Test
    void pay_idempotentReplay_returnsCurrentBalanceWithoutSecondLedgerRow() {
        // Simulate a prior partial payment of 600 already on the ledger.
        // The user's balance is currently 400 (post-prior-payment) and a
        // retry of the same slTransactionId comes in.
        User u = userWithBalance(7L, "Alice", 400L);
        when(userRepo.findIdBySlAvatarUuid(AVATAR)).thenReturn(Optional.of(7L));
        when(userRepo.findById(7L)).thenReturn(Optional.of(u));
        EscrowTransaction prior = EscrowTransaction.builder()
                .type(EscrowTransactionType.LISTING_PENALTY_PAYMENT)
                .status(EscrowTransactionStatus.COMPLETED)
                .amount(600L)
                .payer(u)
                .slTransactionId(SL_TXN)
                .terminalId(TERMINAL)
                .completedAt(NOW)
                .build();
        when(ledgerRepo.findFirstBySlTransactionIdAndType(
                SL_TXN, EscrowTransactionType.LISTING_PENALTY_PAYMENT))
                .thenReturn(Optional.of(prior));

        PenaltyPaymentResponse resp = service.pay(
                new PenaltyPaymentRequest(AVATAR, SL_TXN, 600L, TERMINAL));

        // Replay returns the user's current (already-decremented) balance.
        assertThat(resp.remainingBalance()).isEqualTo(400L);

        // No second decrement, no second ledger row, no lock acquired.
        assertThat(u.getPenaltyBalanceOwed()).isEqualTo(400L);
        verify(userRepo, never()).findByIdForUpdate(any());
        verify(userRepo, never()).save(any(User.class));
        verify(ledgerRepo, never()).save(any(EscrowTransaction.class));
    }

    // -----------------------------------------------------------------
    // Payment — overpayment
    // -----------------------------------------------------------------

    @Test
    void pay_overpayment_throwsAndLeavesStateUnchanged() {
        User u = userWithBalance(7L, "Alice", 500L);
        when(userRepo.findIdBySlAvatarUuid(AVATAR)).thenReturn(Optional.of(7L));
        when(ledgerRepo.findFirstBySlTransactionIdAndType(
                SL_TXN, EscrowTransactionType.LISTING_PENALTY_PAYMENT))
                .thenReturn(Optional.empty());
        when(userRepo.findByIdForUpdate(7L)).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> service.pay(
                new PenaltyPaymentRequest(AVATAR, SL_TXN, 1000L, TERMINAL)))
                .isInstanceOf(PenaltyOverpaymentException.class)
                .satisfies(e -> {
                    PenaltyOverpaymentException ex = (PenaltyOverpaymentException) e;
                    assertThat(ex.getRequested()).isEqualTo(1000L);
                    assertThat(ex.getAvailable()).isEqualTo(500L);
                });

        // Balance untouched, no ledger row, no save.
        assertThat(u.getPenaltyBalanceOwed()).isEqualTo(500L);
        verify(userRepo, never()).save(any(User.class));
        verify(ledgerRepo, never()).save(any(EscrowTransaction.class));
    }

    @Test
    void pay_unknownAvatar_throwsUserNotFound() {
        when(userRepo.findIdBySlAvatarUuid(AVATAR)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pay(
                new PenaltyPaymentRequest(AVATAR, SL_TXN, 100L, TERMINAL)))
                .isInstanceOf(UserNotFoundException.class);

        verify(ledgerRepo, never()).save(any(EscrowTransaction.class));
    }
}
