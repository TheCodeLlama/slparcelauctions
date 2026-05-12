package com.slparcelauctions.backend.realty.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class RealtyGroupPermissionTest {

    @Test
    void dEnumValuesAreDefined() {
        assertThat(RealtyGroupPermission.values())
            .contains(
                RealtyGroupPermission.SPEND_FROM_GROUP_WALLET,
                RealtyGroupPermission.WITHDRAW_FROM_GROUP_WALLET,
                RealtyGroupPermission.VIEW_GROUP_TRANSACTIONS);
    }

    @Test
    void enumContainsExactlyExpectedValuesForF() {
        Set<String> names = Stream.of(RealtyGroupPermission.values())
                .map(Enum::name)
                .collect(Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrder(
                "INVITE_AGENTS", "REMOVE_AGENTS", "EDIT_GROUP_PROFILE", "CONFIGURE_FEES",
                "CREATE_LISTING", "MANAGE_ALL_LISTINGS",
                "SPEND_FROM_GROUP_WALLET", "WITHDRAW_FROM_GROUP_WALLET", "VIEW_GROUP_TRANSACTIONS",
                "REGISTER_SL_GROUP", "MANAGE_MEMBERS");
    }
}
