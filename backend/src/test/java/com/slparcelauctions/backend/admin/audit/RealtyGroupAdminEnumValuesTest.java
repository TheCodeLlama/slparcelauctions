package com.slparcelauctions.backend.admin.audit;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.auction.CancellationOffenseKind;
import com.slparcelauctions.backend.auction.fraud.FraudFlagReason;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the realty-group admin enum values introduced across sub-projects A+B
 * (group lifecycle) and F (admin moderation).
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

    @Test
    void realtyGroupModerationActionTypesExist() {
        // Sub-project F §4 — moderation actions on the group itself.
        assertThat(AdminActionType.REALTY_GROUP_SUSPEND.name())
            .isEqualTo("REALTY_GROUP_SUSPEND");
        assertThat(AdminActionType.REALTY_GROUP_UNSUSPEND.name())
            .isEqualTo("REALTY_GROUP_UNSUSPEND");
        assertThat(AdminActionType.REALTY_GROUP_BAN.name())
            .isEqualTo("REALTY_GROUP_BAN");
        assertThat(AdminActionType.REALTY_GROUP_UNBAN.name())
            .isEqualTo("REALTY_GROUP_UNBAN");
        assertThat(AdminActionType.REALTY_GROUP_FRAUD_FLAG.name())
            .isEqualTo("REALTY_GROUP_FRAUD_FLAG");

        // Sub-project F §4.2 — user-report triage.
        assertThat(AdminActionType.REALTY_GROUP_REPORT_RESOLVE.name())
            .isEqualTo("REALTY_GROUP_REPORT_RESOLVE");
        assertThat(AdminActionType.REALTY_GROUP_REPORT_DISMISS.name())
            .isEqualTo("REALTY_GROUP_REPORT_DISMISS");

        // Sub-project F §4.4 — bulk listing suspend / reinstate + 48 h expiry.
        assertThat(AdminActionType.REALTY_GROUP_BULK_SUSPEND.name())
            .isEqualTo("REALTY_GROUP_BULK_SUSPEND");
        assertThat(AdminActionType.REALTY_GROUP_BULK_REINSTATE.name())
            .isEqualTo("REALTY_GROUP_BULK_REINSTATE");
        assertThat(AdminActionType.REALTY_GROUP_BULK_SUSPEND_EXPIRY_RUN.name())
            .isEqualTo("REALTY_GROUP_BULK_SUSPEND_EXPIRY_RUN");

        // Sub-project F §4.6 — SL group registration ops.
        assertThat(AdminActionType.REALTY_GROUP_SL_GROUP_FORCE_UNREGISTER.name())
            .isEqualTo("REALTY_GROUP_SL_GROUP_FORCE_UNREGISTER");
        assertThat(AdminActionType.REALTY_GROUP_SL_GROUP_DRIFT_ACK.name())
            .isEqualTo("REALTY_GROUP_SL_GROUP_DRIFT_ACK");
        assertThat(AdminActionType.REALTY_GROUP_SL_GROUP_RECHECK.name())
            .isEqualTo("REALTY_GROUP_SL_GROUP_RECHECK");
    }

    @Test
    void realtyGroupFraudFlagReasonsExist() {
        // Sub-project F §4.5 — group-scoped fraud-flag reasons.
        assertThat(FraudFlagReason.REALTY_GROUP_FRAUDULENT_LISTINGS.name())
            .isEqualTo("REALTY_GROUP_FRAUDULENT_LISTINGS");
        assertThat(FraudFlagReason.REALTY_GROUP_IMPERSONATION.name())
            .isEqualTo("REALTY_GROUP_IMPERSONATION");
        assertThat(FraudFlagReason.REALTY_GROUP_REPEATED_REPORTS.name())
            .isEqualTo("REALTY_GROUP_REPEATED_REPORTS");
    }

    @Test
    void adminBulkExpiredCancellationKindExists() {
        // Sub-project F §4.4 — 48 h bulk-suspend-expiry auto-cancel kind.
        // Mirrors BROKER_CANCEL: excluded from the seller penalty ladder by
        // CancellationLogRepository.countPriorOffensesWithBids.
        assertThat(CancellationOffenseKind.ADMIN_BULK_EXPIRED.name())
            .isEqualTo("ADMIN_BULK_EXPIRED");
    }
}
