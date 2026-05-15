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

import com.slparcelauctions.backend.escrow.command.TerminalCommand;
import com.slparcelauctions.backend.escrow.command.TerminalCommandAction;
import com.slparcelauctions.backend.escrow.command.TerminalCommandPurpose;
import com.slparcelauctions.backend.escrow.command.TerminalCommandRepository;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupGuard;
import com.slparcelauctions.backend.realty.moderation.RealtyGroupSuspensionRepository;
import com.slparcelauctions.backend.realty.moderation.exception.RealtyGroupSuspendedException;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroup;
import com.slparcelauctions.backend.realty.slgroup.RealtyGroupSlGroupRepository;
import com.slparcelauctions.backend.realty.wallet.broadcast.GroupWalletBroadcastPublisher;
import com.slparcelauctions.backend.realty.wallet.dto.GroupWithdrawRecipient;
import com.slparcelauctions.backend.realty.wallet.exception.InsufficientGroupBalanceException;
import com.slparcelauctions.backend.realty.wallet.exception.LeaderFrozenException;
import com.slparcelauctions.backend.realty.wallet.exception.LeaderTermsNotAcceptedException;
import com.slparcelauctions.backend.realty.wallet.exception.SlGroupNotRegisteredException;
import com.slparcelauctions.backend.realty.wallet.exception.SlGroupRegistrationSuspendedException;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RealtyGroupWalletService#withdraw},
 * {@link RealtyGroupWalletService#recordWithdrawalSuccess},
 * {@link RealtyGroupWalletService#recordWithdrawalReversal}, and
 * {@link RealtyGroupWalletService#findGroupIdForLedgerEntry}.
 */
class RealtyGroupWalletServiceWithdrawTest {

    private final RealtyGroupRepository groupRepo = mock(RealtyGroupRepository.class);
    private final RealtyGroupLedgerRepository ledgerRepo = mock(RealtyGroupLedgerRepository.class);
    private final UserRepository userRepo = mock(UserRepository.class);
    private final TerminalCommandRepository cmdRepo = mock(TerminalCommandRepository.class);
    private final NotificationPublisher notif = mock(NotificationPublisher.class);
    private final GroupWalletBroadcastPublisher pub = mock(GroupWalletBroadcastPublisher.class);
    private final RealtyGroupGuard guard = mock(RealtyGroupGuard.class);
    private final RealtyGroupSlGroupRepository slGroupRepo = mock(RealtyGroupSlGroupRepository.class);
    private final RealtyGroupSuspensionRepository suspensionRepo =
        mock(RealtyGroupSuspensionRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);

    private final RealtyGroupWalletService svc = new RealtyGroupWalletService(
        groupRepo, ledgerRepo, userRepo, cmdRepo, notif, pub, guard,
        slGroupRepo, suspensionRepo, null, null, clock);

    // ---- withdraw happy path ----

    @Test
    void happyPath_debitsBalanceAppendsLedgerQueuesTerminalCommand() throws Exception {
        RealtyGroup g = group(42L, 1000L);
        User leader = readyLeader(UUID.randomUUID());
        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));
        when(userRepo.findById(g.getLeaderId())).thenReturn(Optional.of(leader));
        when(ledgerRepo.save(any())).thenAnswer(inv -> {
            RealtyGroupLedgerEntry e = inv.getArgument(0);
            setId(e, 123L);
            setPublicId(e, UUID.randomUUID());
            return e;
        });
        when(cmdRepo.save(any())).thenAnswer(inv -> {
            TerminalCommand c = inv.getArgument(0);
            setId(c, 999L);
            return c;
        });
        UUID idemKey = UUID.randomUUID();
        when(ledgerRepo.findByIdempotencyKey(idemKey.toString())).thenReturn(Optional.empty());

        RealtyGroupWalletService.WithdrawResult result =
            svc.withdraw(42L, 500L, idemKey, 5L, GroupWithdrawRecipient.AVATAR);

        assertThat(g.getBalanceLindens()).isEqualTo(500L);
        ArgumentCaptor<TerminalCommand> cmdCap = ArgumentCaptor.forClass(TerminalCommand.class);
        verify(cmdRepo).save(cmdCap.capture());
        TerminalCommand cmd = cmdCap.getValue();
        assertThat(cmd.getPurpose()).isEqualTo(TerminalCommandPurpose.GROUP_WALLET_WITHDRAWAL);
        assertThat(cmd.getRealtyGroupId()).isEqualTo(42L);
        assertThat(cmd.getRecipientUuid()).isEqualTo(leader.getSlAvatarUuid().toString());
        assertThat(cmd.getIdempotencyKey()).isEqualTo("GWAL-123");
        assertThat(result.queueId()).isEqualTo(999L);
        assertThat(result.estimatedFulfillmentSeconds()).isEqualTo(60);

        // Verify ledger row
        ArgumentCaptor<RealtyGroupLedgerEntry> ledgerCap =
            ArgumentCaptor.forClass(RealtyGroupLedgerEntry.class);
        verify(ledgerRepo).save(ledgerCap.capture());
        RealtyGroupLedgerEntry ledgerEntry = ledgerCap.getValue();
        assertThat(ledgerEntry.getEntryType()).isEqualTo(RealtyGroupLedgerEntryType.WITHDRAW_QUEUED);
        assertThat(ledgerEntry.getGroupId()).isEqualTo(42L);
        assertThat(ledgerEntry.getAmount()).isEqualTo(500L);
        assertThat(ledgerEntry.getBalanceAfter()).isEqualTo(500L);
        assertThat(ledgerEntry.getActorUserId()).isEqualTo(5L);
        assertThat(ledgerEntry.getIdempotencyKey()).isEqualTo(idemKey.toString());
        assertThat(ledgerEntry.getRefType()).isEqualTo("TERMINAL_COMMAND");

        verify(pub).publish(any(UUID.class), anyLong(), anyLong(), anyLong(), anyString(), any(UUID.class));
    }

    // ---- suspension guard ----

    @Test
    void rejectsWithdrawWhenGroupSuspended_doesNotMutate() {
        doThrow(new RealtyGroupSuspendedException(
                    RealtyGroupSuspendedException.Status.SUSPENDED,
                    OffsetDateTime.now(clock).plusDays(7), "TOS"))
            .when(guard).requireGroupCanOperate(42L);
        UUID idem = UUID.randomUUID();

        assertThatThrownBy(() -> svc.withdraw(42L, 500L, idem, 5L, GroupWithdrawRecipient.AVATAR))
            .isInstanceOf(RealtyGroupSuspendedException.class);

        verify(groupRepo, never()).findByIdForUpdate(any());
        verify(ledgerRepo, never()).save(any());
        verify(cmdRepo, never()).save(any());
        verify(ledgerRepo, never()).findByIdempotencyKey(anyString());
    }

    // ---- leader validation ----

    @Test
    void rejectsLeaderWithoutAcceptedTerms() throws Exception {
        RealtyGroup g = group(42L, 1000L);
        User leader = readyLeader(UUID.randomUUID());
        leader.setWalletTermsAcceptedAt(null);
        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));
        when(userRepo.findById(g.getLeaderId())).thenReturn(Optional.of(leader));
        UUID idem = UUID.randomUUID();
        when(ledgerRepo.findByIdempotencyKey(idem.toString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.withdraw(42L, 500L, idem, 5L, GroupWithdrawRecipient.AVATAR))
            .isInstanceOf(LeaderTermsNotAcceptedException.class);

        verify(ledgerRepo, never()).save(any());
        verify(cmdRepo, never()).save(any());
    }

    @Test
    void rejectsFrozenLeader() throws Exception {
        RealtyGroup g = group(42L, 1000L);
        User leader = readyLeader(UUID.randomUUID());
        leader.setWalletFrozenAt(OffsetDateTime.now(clock));
        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));
        when(userRepo.findById(g.getLeaderId())).thenReturn(Optional.of(leader));
        UUID idem = UUID.randomUUID();
        when(ledgerRepo.findByIdempotencyKey(idem.toString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.withdraw(42L, 500L, idem, 5L, GroupWithdrawRecipient.AVATAR))
            .isInstanceOf(LeaderFrozenException.class);
    }

    @Test
    void rejectsBannedLeader() throws Exception {
        RealtyGroup g = group(42L, 1000L);
        User leader = readyLeader(UUID.randomUUID());
        leader.setBannedFromListing(true);
        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));
        when(userRepo.findById(g.getLeaderId())).thenReturn(Optional.of(leader));
        UUID idem = UUID.randomUUID();
        when(ledgerRepo.findByIdempotencyKey(idem.toString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.withdraw(42L, 500L, idem, 5L, GroupWithdrawRecipient.AVATAR))
            .isInstanceOf(LeaderFrozenException.class);
    }

    @Test
    void rejectsInsufficientBalance() throws Exception {
        RealtyGroup g = group(42L, 100L);
        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));
        when(userRepo.findById(g.getLeaderId())).thenReturn(Optional.of(readyLeader(UUID.randomUUID())));
        UUID idem = UUID.randomUUID();
        when(ledgerRepo.findByIdempotencyKey(idem.toString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.withdraw(42L, 500L, idem, 5L, GroupWithdrawRecipient.AVATAR))
            .isInstanceOf(InsufficientGroupBalanceException.class);
    }

    // ---- idempotency replay ----

    @Test
    void idempotencyReplay_returnsExistingResultWithoutNewEntries() throws Exception {
        UUID idem = UUID.randomUUID();

        // Pre-existing ledger row with same idempotency key.
        RealtyGroupLedgerEntry existing = RealtyGroupLedgerEntry.builder()
            .groupId(42L)
            .entryType(RealtyGroupLedgerEntryType.WITHDRAW_QUEUED)
            .amount(500L)
            .balanceAfter(500L)
            .reservedAfter(0L)
            .idempotencyKey(idem.toString())
            .build();
        setId(existing, 77L);

        // Pre-existing TerminalCommand with the matching GWAL-{ledgerId} key.
        TerminalCommand priorCmd = TerminalCommand.builder()
            .idempotencyKey("GWAL-77")
            .amount(500L)
            .purpose(TerminalCommandPurpose.GROUP_WALLET_WITHDRAWAL)
            .build();
        setId(priorCmd, 888L);

        when(ledgerRepo.findByIdempotencyKey(idem.toString())).thenReturn(Optional.of(existing));
        when(cmdRepo.findByIdempotencyKey("GWAL-77")).thenReturn(Optional.of(priorCmd));

        RealtyGroupWalletService.WithdrawResult result =
            svc.withdraw(42L, 500L, idem, 5L, GroupWithdrawRecipient.AVATAR);

        // No new ledger row or command should be saved.
        verify(ledgerRepo, never()).save(any());
        verify(cmdRepo, never()).save(any());
        // Returns the prior command's id.
        assertThat(result.queueId()).isEqualTo(888L);
        assertThat(result.estimatedFulfillmentSeconds()).isEqualTo(60);
        // Balance of the group is never touched in a replay — no groupRepo interaction needed.
        verify(groupRepo, never()).findByIdForUpdate(any());
    }

    // ---- recordWithdrawalSuccess ----

    @Test
    void recordWithdrawalSuccess_appendsCompletedRow() throws Exception {
        RealtyGroupLedgerEntry queued = RealtyGroupLedgerEntry.builder()
            .groupId(10L)
            .entryType(RealtyGroupLedgerEntryType.WITHDRAW_QUEUED)
            .amount(300L)
            .balanceAfter(700L)
            .reservedAfter(0L)
            .build();
        setId(queued, 55L);
        when(ledgerRepo.findById(55L)).thenReturn(Optional.of(queued));
        when(ledgerRepo.save(any())).thenAnswer(inv -> {
            RealtyGroupLedgerEntry e = inv.getArgument(0);
            setPublicId(e, UUID.randomUUID());
            return e;
        });

        svc.recordWithdrawalSuccess(55L, "sl-txn-xyz");

        ArgumentCaptor<RealtyGroupLedgerEntry> cap =
            ArgumentCaptor.forClass(RealtyGroupLedgerEntry.class);
        verify(ledgerRepo).save(cap.capture());
        RealtyGroupLedgerEntry saved = cap.getValue();
        assertThat(saved.getEntryType()).isEqualTo(RealtyGroupLedgerEntryType.WITHDRAW_COMPLETED);
        assertThat(saved.getGroupId()).isEqualTo(10L);
        assertThat(saved.getAmount()).isEqualTo(300L);
        assertThat(saved.getBalanceAfter()).isEqualTo(700L);
        assertThat(saved.getSlTransactionId()).isEqualTo("sl-txn-xyz");
        assertThat(saved.getRefType()).isEqualTo("REALTY_GROUP_LEDGER_ENTRY");
        assertThat(saved.getRefId()).isEqualTo(55L);
        // No balance change on group — no groupRepo interaction needed for success.
        verify(groupRepo, never()).findByIdForUpdate(any());
    }

    // ---- recordWithdrawalReversal ----

    @Test
    void recordWithdrawalReversal_creditsBalanceAndAppendsReversedRow() throws Exception {
        RealtyGroup g = group(10L, 700L);
        RealtyGroupLedgerEntry queued = RealtyGroupLedgerEntry.builder()
            .groupId(10L)
            .entryType(RealtyGroupLedgerEntryType.WITHDRAW_QUEUED)
            .amount(300L)
            .balanceAfter(700L)
            .reservedAfter(0L)
            .build();
        setId(queued, 55L);
        when(ledgerRepo.findById(55L)).thenReturn(Optional.of(queued));
        when(groupRepo.findByIdForUpdate(10L)).thenReturn(Optional.of(g));
        when(ledgerRepo.save(any())).thenAnswer(inv -> {
            RealtyGroupLedgerEntry e = inv.getArgument(0);
            setPublicId(e, UUID.randomUUID());
            return e;
        });

        svc.recordWithdrawalReversal(55L, "transport failure");

        assertThat(g.getBalanceLindens()).isEqualTo(1000L);

        ArgumentCaptor<RealtyGroupLedgerEntry> cap =
            ArgumentCaptor.forClass(RealtyGroupLedgerEntry.class);
        verify(ledgerRepo).save(cap.capture());
        RealtyGroupLedgerEntry saved = cap.getValue();
        assertThat(saved.getEntryType()).isEqualTo(RealtyGroupLedgerEntryType.WITHDRAW_REVERSED);
        assertThat(saved.getGroupId()).isEqualTo(10L);
        assertThat(saved.getAmount()).isEqualTo(300L);
        assertThat(saved.getBalanceAfter()).isEqualTo(1000L);
        assertThat(saved.getDescription()).isEqualTo("transport failure");
        assertThat(saved.getRefType()).isEqualTo("REALTY_GROUP_LEDGER_ENTRY");
        assertThat(saved.getRefId()).isEqualTo(55L);

        verify(pub).publish(any(UUID.class), anyLong(), anyLong(), anyLong(), anyString(), any(UUID.class));
    }

    // ---- findGroupIdForLedgerEntry ----

    @Test
    void findGroupIdForLedgerEntry_returnsGroupId() throws Exception {
        RealtyGroupLedgerEntry e = RealtyGroupLedgerEntry.builder()
            .groupId(77L)
            .entryType(RealtyGroupLedgerEntryType.WITHDRAW_QUEUED)
            .amount(1L)
            .balanceAfter(0L)
            .reservedAfter(0L)
            .build();
        when(ledgerRepo.findById(123L)).thenReturn(Optional.of(e));

        assertThat(svc.findGroupIdForLedgerEntry(123L)).isEqualTo(77L);
    }

    @Test
    void findGroupIdForLedgerEntry_returnsNullWhenMissing() {
        when(ledgerRepo.findById(999L)).thenReturn(Optional.empty());

        assertThat(svc.findGroupIdForLedgerEntry(999L)).isNull();
    }

    // ---- SL_GROUP recipient (sub-project G section 7.3) ----

    @Test
    void withdraw_toSlGroup_enqueuesWithdrawGroupCommand_whenRegistrationCurrent() throws Exception {
        RealtyGroup g = group(42L, 1000L);
        User leader = readyLeader(UUID.randomUUID());
        UUID slGroupUuid = UUID.randomUUID();
        RealtyGroupSlGroup reg = RealtyGroupSlGroup.builder()
            .realtyGroupId(42L)
            .slGroupUuid(slGroupUuid)
            .slGroupName("Some Estates")
            .verified(true)
            .verifiedAt(OffsetDateTime.now(clock))
            .build();

        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));
        when(userRepo.findById(g.getLeaderId())).thenReturn(Optional.of(leader));
        when(slGroupRepo.findCurrentRegisteredForRealtyGroup(42L))
            .thenReturn(java.util.List.of(reg));
        when(suspensionRepo.existsActiveForGroup(eq(42L), any()))
            .thenReturn(false);
        when(ledgerRepo.save(any())).thenAnswer(inv -> {
            RealtyGroupLedgerEntry e = inv.getArgument(0);
            setId(e, 200L);
            setPublicId(e, UUID.randomUUID());
            return e;
        });
        when(cmdRepo.save(any())).thenAnswer(inv -> {
            TerminalCommand c = inv.getArgument(0);
            setId(c, 777L);
            return c;
        });
        UUID idem = UUID.randomUUID();
        when(ledgerRepo.findByIdempotencyKey(idem.toString())).thenReturn(Optional.empty());

        RealtyGroupWalletService.WithdrawResult result =
            svc.withdraw(42L, 500L, idem, 5L, GroupWithdrawRecipient.SL_GROUP);

        ArgumentCaptor<TerminalCommand> cmdCap = ArgumentCaptor.forClass(TerminalCommand.class);
        verify(cmdRepo).save(cmdCap.capture());
        TerminalCommand cmd = cmdCap.getValue();
        assertThat(cmd.getAction()).isEqualTo(TerminalCommandAction.WITHDRAW_GROUP);
        assertThat(cmd.getRecipientUuid()).isEqualTo(slGroupUuid.toString());
        assertThat(cmd.getRealtyGroupId()).isEqualTo(42L);
        assertThat(result.queueId()).isEqualTo(777L);

        ArgumentCaptor<RealtyGroupLedgerEntry> ledgerCap =
            ArgumentCaptor.forClass(RealtyGroupLedgerEntry.class);
        verify(ledgerRepo).save(ledgerCap.capture());
        assertThat(ledgerCap.getValue().getDescription()).isEqualTo("to SL group Some Estates");
    }

    @Test
    void withdraw_toSlGroup_throwsSlGroupNotRegistered_whenNoRegistrationExists() throws Exception {
        RealtyGroup g = group(42L, 1000L);
        User leader = readyLeader(UUID.randomUUID());
        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));
        when(userRepo.findById(g.getLeaderId())).thenReturn(Optional.of(leader));
        when(slGroupRepo.findCurrentRegisteredForRealtyGroup(42L))
            .thenReturn(java.util.List.of());
        UUID idem = UUID.randomUUID();
        when(ledgerRepo.findByIdempotencyKey(idem.toString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.withdraw(42L, 500L, idem, 5L, GroupWithdrawRecipient.SL_GROUP))
            .isInstanceOf(SlGroupNotRegisteredException.class);

        verify(ledgerRepo, never()).save(any());
        verify(cmdRepo, never()).save(any());
    }

    @Test
    void withdraw_toSlGroup_throwsSlGroupRegistrationSuspended_whenActiveSuspension() throws Exception {
        RealtyGroup g = group(42L, 1000L);
        User leader = readyLeader(UUID.randomUUID());
        RealtyGroupSlGroup reg = RealtyGroupSlGroup.builder()
            .realtyGroupId(42L)
            .slGroupUuid(UUID.randomUUID())
            .verified(true)
            .verifiedAt(OffsetDateTime.now(clock))
            .build();
        when(groupRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(g));
        when(userRepo.findById(g.getLeaderId())).thenReturn(Optional.of(leader));
        when(slGroupRepo.findCurrentRegisteredForRealtyGroup(42L))
            .thenReturn(java.util.List.of(reg));
        when(suspensionRepo.existsActiveForGroup(eq(42L), any())).thenReturn(true);
        UUID idem = UUID.randomUUID();
        when(ledgerRepo.findByIdempotencyKey(idem.toString())).thenReturn(Optional.empty());

        // The realty-group guard (mocked, no-op by default) does not fire in this test --
        // the explicit existsActiveForGroup re-check inside the SL_GROUP branch is what
        // surfaces SlGroupRegistrationSuspendedException. This mirrors the production
        // semantic ("SL_GROUP path requires the realty group to be operable") that
        // survives any future loosening of the guard at the top of withdraw.
        assertThatThrownBy(() -> svc.withdraw(42L, 500L, idem, 5L, GroupWithdrawRecipient.SL_GROUP))
            .isInstanceOf(SlGroupRegistrationSuspendedException.class);

        verify(ledgerRepo, never()).save(any());
        verify(cmdRepo, never()).save(any());
    }

    @Test
    void withdraw_nullRecipient_throwsIllegalArgument() {
        UUID idem = UUID.randomUUID();
        assertThatThrownBy(() -> svc.withdraw(42L, 500L, idem, 5L, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("recipient");
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

    private User readyLeader(UUID avatarUuid) {
        return User.builder()
            .username("leader")
            .passwordHash("x")
            .slAvatarUuid(avatarUuid)
            .walletTermsAcceptedAt(OffsetDateTime.now(clock))
            .build();
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
