package com.slparcelauctions.backend.realty.wallet;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupGuard;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupSuspensionRepository;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupRepository;
import com.slparcelauctions.backend.realty.wallet.broadcast.GroupWalletBroadcastPublisher;
import com.slparcelauctions.backend.realty.wallet.exception.DepositAmountOutOfRangeException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;
import com.slparcelauctions.backend.wallet.UserLedgerEntry;
import com.slparcelauctions.backend.wallet.UserLedgerEntryType;
import com.slparcelauctions.backend.wallet.UserLedgerRepository;
import com.slparcelauctions.backend.wallet.broadcast.WalletBroadcastPublisher;
import com.slparcelauctions.backend.wallet.exception.InsufficientAvailableBalanceException;
import com.slparcelauctions.backend.wallet.exception.UserStatusBlockedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RealtyGroupWalletService#depositFromMemberWallet}.
 *
 * <p>Mock-based to match the pattern used by the other
 * {@code RealtyGroupWalletService*Test} files in this package. The five
 * required scenarios (happy path, insufficient balance, range-min,
 * range-max, idempotent replay) are exercised with explicit lock-method
 * stubs (no in-memory Spring context needed).
 */
class RealtyGroupWalletServiceDepositTest {

    private static final long DEFAULT_MAX = 500_000L;

    private final RealtyGroupRepository groupRepo = mock(RealtyGroupRepository.class);
    private final RealtyGroupLedgerRepository ledgerRepo = mock(RealtyGroupLedgerRepository.class);
    private final UserRepository userRepo = mock(UserRepository.class);
    private final TerminalCommandRepository cmdRepo = mock(TerminalCommandRepository.class);
    private final NotificationPublisher notif = mock(NotificationPublisher.class);
    private final GroupWalletBroadcastPublisher groupBroadcast =
        mock(GroupWalletBroadcastPublisher.class);
    private final RealtyGroupGuard guard = mock(RealtyGroupGuard.class);
    private final RealtyGroupSlGroupRepository slGroupRepo =
        mock(RealtyGroupSlGroupRepository.class);
    private final RealtyGroupSuspensionRepository suspensionRepo =
        mock(RealtyGroupSuspensionRepository.class);
    private final UserLedgerRepository userLedgerRepo = mock(UserLedgerRepository.class);
    private final WalletBroadcastPublisher userBroadcast = mock(WalletBroadcastPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-14T10:00:00Z"), ZoneOffset.UTC);

    private final RealtyGroupWalletService svc = buildService(DEFAULT_MAX);

    private RealtyGroupWalletService buildService(long maxL) {
        RealtyGroupWalletService s = new RealtyGroupWalletService(
            groupRepo, ledgerRepo, userRepo, cmdRepo, notif, groupBroadcast, guard,
            slGroupRepo, suspensionRepo, userLedgerRepo, userBroadcast, clock);
        try {
            Field f = RealtyGroupWalletService.class.getDeclaredField("groupDepositMaxL");
            f.setAccessible(true);
            f.setLong(s, maxL);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
        return s;
    }

    // ---- 1. Happy path ----

    @Test
    void deposit_credits_group_and_debits_user_atomically() throws Exception {
        RealtyGroup g = group(42L, 1_000L);
        User u = user(7L, 5_000L, 0L);
        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));
        when(userRepo.findByIdForUpdate(7L)).thenReturn(Optional.of(u));
        when(ledgerRepo.save(any())).thenAnswer(inv -> {
            RealtyGroupLedgerEntry e = inv.getArgument(0);
            setId(e, 555L);
            setPublicId(e, UUID.randomUUID());
            return e;
        });
        when(userLedgerRepo.save(any())).thenAnswer(inv -> {
            UserLedgerEntry e = inv.getArgument(0);
            setId(e, 777L);
            setPublicId(e, UUID.randomUUID());
            return e;
        });
        UUID idem = UUID.randomUUID();
        when(ledgerRepo.findByIdempotencyKey(idem.toString())).thenReturn(Optional.empty());

        RealtyGroupWalletService.DepositResult result =
            svc.depositFromMemberWallet(42L, 300L, 7L, "Reimbursement", idem);

        // Balances updated.
        assertThat(g.getBalanceLindens()).isEqualTo(1_300L);
        assertThat(u.getBalanceLindens()).isEqualTo(4_700L);

        // Group-ledger row inspected.
        ArgumentCaptor<RealtyGroupLedgerEntry> groupCap =
            ArgumentCaptor.forClass(RealtyGroupLedgerEntry.class);
        verify(ledgerRepo).save(groupCap.capture());
        RealtyGroupLedgerEntry groupRow = groupCap.getValue();
        assertThat(groupRow.getGroupId()).isEqualTo(42L);
        assertThat(groupRow.getEntryType())
            .isEqualTo(RealtyGroupLedgerEntryType.MEMBER_DEPOSIT);
        assertThat(groupRow.getAmount()).isEqualTo(300L);
        assertThat(groupRow.getBalanceAfter()).isEqualTo(1_300L);
        assertThat(groupRow.getReservedAfter()).isEqualTo(0L);
        assertThat(groupRow.getActorUserId()).isEqualTo(7L);
        assertThat(groupRow.getIdempotencyKey()).isEqualTo(idem.toString());
        assertThat(groupRow.getDescription()).isEqualTo("Deposit from app wallet -- Reimbursement");

        // User-ledger row inspected.
        ArgumentCaptor<UserLedgerEntry> userCap = ArgumentCaptor.forClass(UserLedgerEntry.class);
        verify(userLedgerRepo).save(userCap.capture());
        UserLedgerEntry userRow = userCap.getValue();
        assertThat(userRow.getUserId()).isEqualTo(7L);
        assertThat(userRow.getEntryType())
            .isEqualTo(UserLedgerEntryType.GROUP_WALLET_DEPOSIT_DEBIT);
        assertThat(userRow.getAmount()).isEqualTo(300L);
        assertThat(userRow.getBalanceAfter()).isEqualTo(4_700L);
        assertThat(userRow.getReservedAfter()).isEqualTo(0L);
        assertThat(userRow.getRefType()).isEqualTo("REALTY_GROUP");
        assertThat(userRow.getRefId()).isEqualTo(42L);
        assertThat(userRow.getIdempotencyKey()).isEqualTo(idem.toString());
        assertThat(userRow.getDescription()).isEqualTo("Deposit to Acme -- Reimbursement");

        // Both rows share the idempotency key exactly.
        assertThat(groupRow.getIdempotencyKey()).isEqualTo(userRow.getIdempotencyKey());

        // Broadcasts.
        verify(groupBroadcast).publish(
            any(UUID.class), anyLong(), anyLong(), anyLong(), anyString(), any(UUID.class));
        verify(userBroadcast).publish(any(User.class), anyString(), any(UUID.class));

        // Result shape.
        assertThat(result.groupLedgerEntryId()).isEqualTo(555L);
        assertThat(result.personalLedgerEntryId()).isEqualTo(777L);
        assertThat(result.newGroupAvailable()).isEqualTo(1_300L);
        assertThat(result.newPersonalAvailable()).isEqualTo(4_700L);
    }

    // ---- 2. Insufficient balance ----

    @Test
    void deposit_rejects_insufficient_balance() throws Exception {
        RealtyGroup g = group(42L, 1_000L);
        User u = user(7L, 100L, 0L);
        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));
        when(userRepo.findByIdForUpdate(7L)).thenReturn(Optional.of(u));
        UUID idem = UUID.randomUUID();
        when(ledgerRepo.findByIdempotencyKey(idem.toString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.depositFromMemberWallet(42L, 500L, 7L, null, idem))
            .isInstanceOf(InsufficientAvailableBalanceException.class);

        // No ledger rows, no balance mutation.
        verify(ledgerRepo, never()).save(any());
        verify(userLedgerRepo, never()).save(any());
        assertThat(g.getBalanceLindens()).isEqualTo(1_000L);
        assertThat(u.getBalanceLindens()).isEqualTo(100L);
    }

    // ---- 2b. Reserved L$ are not spendable for deposits ----

    @Test
    void deposit_rejects_when_balance_minus_reserved_is_below_amount() throws Exception {
        // Regression: depositFromMemberWallet must gate on availableLindens()
        // (= balance - reserved), not getBalanceLindens(). Reserved L$ are
        // earmarked for active bids and must remain backed by balance after
        // the debit, otherwise the DB's CHECK (balance >= reserved) violates
        // at COMMIT.
        RealtyGroup g = group(42L, 1_000L);
        User u = user(7L, 1_000L, 900L);   // balance 1000, reserved 900 -> available 100
        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));
        when(userRepo.findByIdForUpdate(7L)).thenReturn(Optional.of(u));
        UUID idem = UUID.randomUUID();
        when(ledgerRepo.findByIdempotencyKey(idem.toString())).thenReturn(Optional.empty());

        // 500 > available (100), even though 500 < balance (1000).
        assertThatThrownBy(() -> svc.depositFromMemberWallet(42L, 500L, 7L, null, idem))
            .isInstanceOf(InsufficientAvailableBalanceException.class);

        verify(ledgerRepo, never()).save(any());
        verify(userLedgerRepo, never()).save(any());
        assertThat(g.getBalanceLindens()).isEqualTo(1_000L);
        assertThat(u.getBalanceLindens()).isEqualTo(1_000L);
    }

    // ---- 3. Range check -- minimum ----

    @Test
    void deposit_rejects_amount_out_of_range_min() {
        UUID idem = UUID.randomUUID();
        assertThatThrownBy(() -> svc.depositFromMemberWallet(42L, 0L, 7L, null, idem))
            .isInstanceOf(DepositAmountOutOfRangeException.class);

        // Range check fires before any repository interaction.
        verify(ledgerRepo, never()).findByIdempotencyKey(anyString());
        verify(groupRepo, never()).findByIdForUpdate(anyLong());
        verify(userRepo, never()).findByIdForUpdate(anyLong());
        verify(ledgerRepo, never()).save(any());
        verify(userLedgerRepo, never()).save(any());
    }

    // ---- 4. Range check -- maximum ----

    @Test
    void deposit_rejects_amount_out_of_range_max() {
        UUID idem = UUID.randomUUID();
        // Default max is 500_000; exceed it.
        assertThatThrownBy(() -> svc.depositFromMemberWallet(42L, DEFAULT_MAX + 1L, 7L, null, idem))
            .isInstanceOf(DepositAmountOutOfRangeException.class);

        verify(ledgerRepo, never()).findByIdempotencyKey(anyString());
        verify(groupRepo, never()).findByIdForUpdate(anyLong());
        verify(userRepo, never()).findByIdForUpdate(anyLong());
    }

    // ---- 5. Idempotent replay ----

    @Test
    void deposit_idempotent_replay_returns_same_ids() throws Exception {
        UUID idem = UUID.randomUUID();
        RealtyGroup curGroup = group(42L, 2_000L);
        User curUser = user(7L, 8_000L, 500L);

        RealtyGroupLedgerEntry priorGroup = RealtyGroupLedgerEntry.builder()
            .groupId(42L)
            .entryType(RealtyGroupLedgerEntryType.MEMBER_DEPOSIT)
            .amount(300L)
            .balanceAfter(1_300L)
            .reservedAfter(0L)
            .idempotencyKey(idem.toString())
            .actorUserId(7L)
            .build();
        setId(priorGroup, 555L);

        UserLedgerEntry priorUser = UserLedgerEntry.builder()
            .userId(7L)
            .entryType(UserLedgerEntryType.GROUP_WALLET_DEPOSIT_DEBIT)
            .amount(300L)
            .balanceAfter(4_700L)
            .reservedAfter(0L)
            .idempotencyKey(idem.toString())
            .build();
        setId(priorUser, 777L);

        when(ledgerRepo.findByIdempotencyKey(idem.toString())).thenReturn(Optional.of(priorGroup));
        when(userLedgerRepo.findByIdempotencyKey(idem.toString())).thenReturn(Optional.of(priorUser));
        when(groupRepo.findById(42L)).thenReturn(Optional.of(curGroup));
        when(userRepo.findById(7L)).thenReturn(Optional.of(curUser));

        // First replay call.
        RealtyGroupWalletService.DepositResult r1 =
            svc.depositFromMemberWallet(42L, 300L, 7L, "memo", idem);
        // Second replay call -- same UUID, same prior rows -- still no new save.
        RealtyGroupWalletService.DepositResult r2 =
            svc.depositFromMemberWallet(42L, 300L, 7L, "memo", idem);

        // Both replays return the same prior IDs.
        assertThat(r1.groupLedgerEntryId()).isEqualTo(555L);
        assertThat(r1.personalLedgerEntryId()).isEqualTo(777L);
        assertThat(r2.groupLedgerEntryId()).isEqualTo(555L);
        assertThat(r2.personalLedgerEntryId()).isEqualTo(777L);

        // Availabilities come from the CURRENT entity state (group: 2000-0,
        // user: 8000-500).
        assertThat(r1.newGroupAvailable()).isEqualTo(2_000L);
        assertThat(r1.newPersonalAvailable()).isEqualTo(7_500L);

        // No new ledger row, no lock acquired, no broadcast.
        verify(ledgerRepo, never()).save(any());
        verify(userLedgerRepo, never()).save(any());
        verify(groupRepo, never()).findByIdForUpdate(anyLong());
        verify(userRepo, never()).findByIdForUpdate(anyLong());
        verify(groupBroadcast, never()).publish(
            any(UUID.class), anyLong(), anyLong(), anyLong(), anyString(), any(UUID.class));
        verify(userBroadcast, never()).publish(any(User.class), anyString(), any(UUID.class));

        // Balances on the entities returned by findById are unchanged.
        assertThat(curGroup.getBalanceLindens()).isEqualTo(2_000L);
        assertThat(curUser.getBalanceLindens()).isEqualTo(8_000L);
    }

    // ---- Bonus: frozen-user rejection ----

    @Test
    void deposit_rejects_frozen_user() throws Exception {
        RealtyGroup g = group(42L, 1_000L);
        User u = user(7L, 5_000L, 0L);
        u.setWalletFrozenAt(OffsetDateTime.now(clock));
        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));
        when(userRepo.findByIdForUpdate(7L)).thenReturn(Optional.of(u));
        UUID idem = UUID.randomUUID();
        when(ledgerRepo.findByIdempotencyKey(idem.toString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.depositFromMemberWallet(42L, 300L, 7L, null, idem))
            .isInstanceOf(UserStatusBlockedException.class);

        verify(ledgerRepo, never()).save(any());
        verify(userLedgerRepo, never()).save(any());
    }

    // ---- helpers ----

    private RealtyGroup group(Long id, long balance) throws Exception {
        RealtyGroup g = RealtyGroup.builder()
            .name("Acme").slug("acme").leaderId(1L)
            .balanceLindens(balance).reservedLindens(0L).build();
        setId(g, id);
        setPublicId(g, UUID.randomUUID());
        return g;
    }

    private User user(Long id, long balance, long reserved) throws Exception {
        User u = User.builder()
            .username("member")
            .passwordHash("x")
            .slAvatarUuid(UUID.randomUUID())
            .balanceLindens(balance)
            .reservedLindens(reserved)
            .walletTermsAcceptedAt(OffsetDateTime.now(clock))
            .build();
        setId(u, id);
        setPublicId(u, UUID.randomUUID());
        return u;
    }

    private static void setId(Object entity, Long id) throws Exception {
        Field f = com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(entity, id);
    }

    private static void setPublicId(Object entity, UUID publicId) throws Exception {
        Field f = com.slparcelauctions.backend.common.BaseEntity.class.getDeclaredField("publicId");
        f.setAccessible(true);
        f.set(entity, publicId);
    }
}
