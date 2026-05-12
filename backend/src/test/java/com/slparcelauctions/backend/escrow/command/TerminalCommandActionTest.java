package com.slparcelauctions.backend.escrow.command;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TerminalCommandActionTest {

    @Test
    void withdraw_group_enum_value_present() {
        assertThat(TerminalCommandAction.valueOf("WITHDRAW_GROUP"))
            .isEqualTo(TerminalCommandAction.WITHDRAW_GROUP);
    }
}
