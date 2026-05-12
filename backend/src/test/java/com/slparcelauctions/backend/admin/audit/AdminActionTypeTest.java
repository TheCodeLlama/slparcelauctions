package com.slparcelauctions.backend.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminActionTypeTest {

    @Test
    void realty_group_wallet_admin_adjustment_enum_value_present() {
        assertThat(AdminActionType.valueOf("REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT"))
            .isEqualTo(AdminActionType.REALTY_GROUP_WALLET_ADMIN_ADJUSTMENT);
    }
}
