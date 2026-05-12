package com.slparcelauctions.backend.escrow.command;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TerminalCommandGroupWalletTest {

    @Test
    void groupWalletWithdrawalPurposeIsDefined() {
        assertThat(TerminalCommandPurpose.values())
            .contains(TerminalCommandPurpose.GROUP_WALLET_WITHDRAWAL);
    }

    @Test
    void realtyGroupIdFieldIsBuildableAndReadable() {
        TerminalCommand cmd = TerminalCommand.builder()
            .action(TerminalCommandAction.WITHDRAW)
            .purpose(TerminalCommandPurpose.GROUP_WALLET_WITHDRAWAL)
            .recipientUuid("00000000-0000-0000-0000-000000000001")
            .amount(500L)
            .status(TerminalCommandStatus.QUEUED)
            .idempotencyKey("GWAL-1")
            .realtyGroupId(42L)
            .build();
        assertThat(cmd.getRealtyGroupId()).isEqualTo(42L);
        assertThat(cmd.getPurpose())
            .isEqualTo(TerminalCommandPurpose.GROUP_WALLET_WITHDRAWAL);
    }
}
