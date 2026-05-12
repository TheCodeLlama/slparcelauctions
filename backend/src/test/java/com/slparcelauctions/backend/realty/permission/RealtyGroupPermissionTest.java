package com.slparcelauctions.backend.realty.permission;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RealtyGroupPermissionTest {

    @Test
    void dEnumValuesAreDefined() {
        assertThat(RealtyGroupPermission.values())
            .contains(
                RealtyGroupPermission.SPEND_FROM_GROUP_WALLET,
                RealtyGroupPermission.WITHDRAW_FROM_GROUP_WALLET,
                RealtyGroupPermission.VIEW_GROUP_TRANSACTIONS);
    }
}
