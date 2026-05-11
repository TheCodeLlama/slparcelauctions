package com.slparcelauctions.backend.admin.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the three realty-group admin enum values introduced for Phase 8.
 *
 * <p>The CHECK constraint in Postgres is widened at startup by
 * {@link AdminActionTypeCheckConstraintInitializer}, which reads
 * {@link Enum#values()} directly — so as long as these enum constants exist on
 * the type, the DB-side constraint matches. This test guards the enum source of
 * truth.
 */
class RealtyGroupAdminEnumValuesTest {

    @Test
    void realtyGroupActionTypesExist() {
        assertThat(AdminActionType.REALTY_GROUP_EDIT.name())
            .isEqualTo("REALTY_GROUP_EDIT");
        assertThat(AdminActionType.REALTY_GROUP_DISSOLVE.name())
            .isEqualTo("REALTY_GROUP_DISSOLVE");
        assertThat(AdminActionType.REALTY_GROUP_MEMBER_REMOVE.name())
            .isEqualTo("REALTY_GROUP_MEMBER_REMOVE");
    }

    @Test
    void realtyGroupTargetTypeExists() {
        assertThat(AdminActionTargetType.REALTY_GROUP.name())
            .isEqualTo("REALTY_GROUP");
    }
}
