package com.slparcelauctions.backend.admin.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserDeletedByAdminEnumTest {

    @Test
    void userDeletedByAdminEnumValueExists() {
        assertThat(AdminActionType.USER_DELETED_BY_ADMIN.name())
                .isEqualTo("USER_DELETED_BY_ADMIN");
    }
}
