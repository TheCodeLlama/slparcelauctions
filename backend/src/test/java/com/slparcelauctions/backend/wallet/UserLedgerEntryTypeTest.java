package com.slparcelauctions.backend.wallet;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class UserLedgerEntryTypeTest {

    @Test
    void agentFeeCreditIsDefined() {
        assertThat(UserLedgerEntryType.values())
            .contains(UserLedgerEntryType.AGENT_FEE_CREDIT);
    }
}
