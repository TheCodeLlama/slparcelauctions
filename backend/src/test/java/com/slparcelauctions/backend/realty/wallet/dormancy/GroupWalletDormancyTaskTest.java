package com.slparcelauctions.backend.realty.wallet.dormancy;

import java.lang.reflect.Field;
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
import com.slparcelauctions.backend.escrow.command.TerminalCommandStatus;
import com.slparcelauctions.backend.notification.NotificationPublisher;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntry;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerEntryType;
import com.slparcelauctions.backend.realty.wallet.RealtyGroupLedgerRepository;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GroupWalletDormancyTaskTest {

    private final RealtyGroupRepository groupRepo = mock(RealtyGroupRepository.class);
    private final RealtyGroupLedgerRepository ledgerRepo = mock(RealtyGroupLedgerRepository.class);
    private final TerminalCommandRepository cmdRepo = mock(TerminalCommandRepository.class);
    private final UserRepository userRepo = mock(UserRepository.class);
    private final NotificationPublisher notif = mock(NotificationPublisher.class);

    private final GroupWalletDormancyTask task = new GroupWalletDormancyTask(
        groupRepo, ledgerRepo, cmdRepo, userRepo, notif);

    private static final OffsetDateTime NOW =
        OffsetDateTime.of(2026, 5, 11, 4, 30, 0, 0, ZoneOffset.UTC);

    // -------------------------------------------------------------------------
    // flag — phase 1
    // -------------------------------------------------------------------------

    @Test
    void phase1FlagsGroup() throws Exception {
        RealtyGroup g = group(10L, 1000L);

        task.flag(g, NOW);

        assertThat(g.getWalletDormancyPhase()).isEqualTo((short) 1);
        assertThat(g.getWalletDormancyStartedAt()).isEqualTo(NOW);
        verify(groupRepo).save(g);
        verify(notif).groupWalletDormancyFlagged(10L, 1, 1000L);
    }

    // -------------------------------------------------------------------------
    // escalateOrAutoReturn — phases 2 and 3
    // -------------------------------------------------------------------------

    @Test
    void phase1To2_escalatesAndSendsIm() throws Exception {
        RealtyGroup g = group(10L, 500L);
        g.setWalletDormancyPhase((short) 1);
        g.setWalletDormancyStartedAt(NOW.minusDays(7));

        task.escalateOrAutoReturn(g, NOW);

        assertThat(g.getWalletDormancyPhase()).isEqualTo((short) 2);
        verify(groupRepo).save(g);
        verify(notif).groupWalletDormancyFlagged(10L, 2, 500L);
    }

    @Test
    void phase2To3_escalatesAndSendsIm() throws Exception {
        RealtyGroup g = group(20L, 750L);
        g.setWalletDormancyPhase((short) 2);
        g.setWalletDormancyStartedAt(NOW.minusDays(14));

        task.escalateOrAutoReturn(g, NOW);

        assertThat(g.getWalletDormancyPhase()).isEqualTo((short) 3);
        verify(groupRepo).save(g);
        verify(notif).groupWalletDormancyFlagged(20L, 3, 750L);
    }

    @Test
    void phase3To4_escalatesAndSendsIm() throws Exception {
        RealtyGroup g = group(30L, 200L);
        g.setWalletDormancyPhase((short) 3);
        g.setWalletDormancyStartedAt(NOW.minusDays(21));

        task.escalateOrAutoReturn(g, NOW);

        assertThat(g.getWalletDormancyPhase()).isEqualTo((short) 4);
        verify(groupRepo).save(g);
        verify(notif).groupWalletDormancyFlagged(30L, 4, 200L);
    }

    // -------------------------------------------------------------------------
    // escalateOrAutoReturn — phase 4 -> auto-return (COMPLETED / phase 99)
    // -------------------------------------------------------------------------

    @Test
    void phase4QueuesAutoReturnAndStampsPhase99() throws Exception {
        UUID leaderAvatarUuid = UUID.randomUUID();
        User leader = leaderWithAvatar(leaderAvatarUuid);
        RealtyGroup g = group(42L, 500L);
        g.setWalletDormancyPhase((short) 4);
        g.setWalletDormancyStartedAt(NOW.minusDays(28));

        when(userRepo.findById(1L)).thenReturn(Optional.of(leader));
        when(cmdRepo.save(any(TerminalCommand.class))).thenAnswer(inv -> {
            TerminalCommand cmd = inv.getArgument(0);
            setId(cmd, 999L);
            return cmd;
        });
        when(ledgerRepo.save(any(RealtyGroupLedgerEntry.class))).thenAnswer(inv -> {
            RealtyGroupLedgerEntry e = inv.getArgument(0);
            setId(e, 77L);
            return e;
        });

        task.escalateOrAutoReturn(g, NOW);

        // Balance zeroed
        assertThat(g.getBalanceLindens()).isEqualTo(0L);
        // Phase stamped 99
        assertThat(g.getWalletDormancyPhase()).isEqualTo((short) 99);

        // TerminalCommand assertions
        ArgumentCaptor<TerminalCommand> cmdCap = ArgumentCaptor.forClass(TerminalCommand.class);
        verify(cmdRepo).save(cmdCap.capture());
        TerminalCommand cmd = cmdCap.getValue();
        assertThat(cmd.getAction()).isEqualTo(TerminalCommandAction.WITHDRAW);
        assertThat(cmd.getPurpose()).isEqualTo(TerminalCommandPurpose.GROUP_WALLET_WITHDRAWAL);
        assertThat(cmd.getAmount()).isEqualTo(500L);
        assertThat(cmd.getRecipientUuid()).isEqualTo(leaderAvatarUuid.toString());
        assertThat(cmd.getStatus()).isEqualTo(TerminalCommandStatus.QUEUED);
        assertThat(cmd.getRealtyGroupId()).isEqualTo(42L);
        assertThat(cmd.getIdempotencyKey()).startsWith("group-dormancy-42-");
        assertThat(cmd.getAttemptCount()).isEqualTo(0);
        assertThat(cmd.getRequiresManualReview()).isFalse();

        // Ledger row assertions
        ArgumentCaptor<RealtyGroupLedgerEntry> ledgerCap =
            ArgumentCaptor.forClass(RealtyGroupLedgerEntry.class);
        verify(ledgerRepo).save(ledgerCap.capture());
        RealtyGroupLedgerEntry ledger = ledgerCap.getValue();
        assertThat(ledger.getEntryType()).isEqualTo(RealtyGroupLedgerEntryType.DORMANCY_AUTO_RETURN);
        assertThat(ledger.getGroupId()).isEqualTo(42L);
        assertThat(ledger.getAmount()).isEqualTo(500L);
        assertThat(ledger.getBalanceAfter()).isEqualTo(0L);
        assertThat(ledger.getRefType()).isEqualTo("TERMINAL_COMMAND");
        assertThat(ledger.getDescription()).contains("auto-return");

        verify(notif).groupWalletDormancyAutoReturned(42L, 500L);
    }

    // -------------------------------------------------------------------------
    // clearForGroup — member login clears dormancy state
    // -------------------------------------------------------------------------

    @Test
    void memberLoginClearsDormancyState() throws Exception {
        RealtyGroup g = group(55L, 300L);
        g.setWalletDormancyPhase((short) 2);
        g.setWalletDormancyStartedAt(NOW.minusDays(14));
        when(groupRepo.findById(55L)).thenReturn(Optional.of(g));

        task.clearForGroup(55L);

        assertThat(g.getWalletDormancyPhase()).isNull();
        assertThat(g.getWalletDormancyStartedAt()).isNull();
        verify(groupRepo).save(g);
    }

    @Test
    void clearForGroup_doesNothingForNonDormantGroup() throws Exception {
        RealtyGroup g = group(60L, 100L);
        // phase is null — not dormant
        when(groupRepo.findById(60L)).thenReturn(Optional.of(g));

        task.clearForGroup(60L);

        verify(groupRepo, never()).save(any());
    }

    @Test
    void clearForGroup_doesNotClearCompletedPhase99() throws Exception {
        RealtyGroup g = group(70L, 0L);
        g.setWalletDormancyPhase((short) 99);
        when(groupRepo.findById(70L)).thenReturn(Optional.of(g));

        task.clearForGroup(70L);

        // Phase 99 (COMPLETED) must not be cleared
        assertThat(g.getWalletDormancyPhase()).isEqualTo((short) 99);
        verify(groupRepo, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Leader change mid-cycle: phase 4 uses current leader's avatar
    // -------------------------------------------------------------------------

    @Test
    void leaderChangeMidCycleUsesNewLeaderAtPhase4() throws Exception {
        UUID originalLeaderAvatar = UUID.randomUUID();
        UUID newLeaderAvatar = UUID.randomUUID();
        User newLeader = leaderWithAvatar(newLeaderAvatar);

        // Group was originally started with leaderId=1 but now leaderId=2 (new leader)
        RealtyGroup g = group(80L, 400L);
        g.setLeaderId(2L);  // leadership transferred
        g.setWalletDormancyPhase((short) 4);
        g.setWalletDormancyStartedAt(NOW.minusDays(28));

        when(userRepo.findById(2L)).thenReturn(Optional.of(newLeader));
        when(cmdRepo.save(any(TerminalCommand.class))).thenAnswer(inv -> {
            TerminalCommand cmd = inv.getArgument(0);
            setId(cmd, 555L);
            return cmd;
        });
        when(ledgerRepo.save(any(RealtyGroupLedgerEntry.class))).thenAnswer(inv ->
            inv.getArgument(0));

        task.escalateOrAutoReturn(g, NOW);

        ArgumentCaptor<TerminalCommand> cmdCap = ArgumentCaptor.forClass(TerminalCommand.class);
        verify(cmdRepo).save(cmdCap.capture());
        // TerminalCommand.recipientUuid must be the NEW leader's avatar, not the original
        assertThat(cmdCap.getValue().getRecipientUuid()).isEqualTo(newLeaderAvatar.toString());
        assertThat(cmdCap.getValue().getRecipientUuid()).isNotEqualTo(originalLeaderAvatar.toString());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private RealtyGroup group(Long id, long balance) throws Exception {
        RealtyGroup g = RealtyGroup.builder()
            .name("Test Group").slug("test-group").leaderId(1L)
            .balanceLindens(balance).reservedLindens(0L)
            .build();
        setId(g, id);
        setPublicId(g, UUID.randomUUID());
        return g;
    }

    private User leaderWithAvatar(UUID avatarUuid) {
        return User.builder()
            .username("leader")
            .passwordHash("x")
            .slAvatarUuid(avatarUuid)
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
