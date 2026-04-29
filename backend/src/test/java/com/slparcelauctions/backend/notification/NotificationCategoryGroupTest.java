package com.slparcelauctions.backend.notification;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class NotificationCategoryGroupTest {

    @Test
    void disputeFiledAgainstSellerIsInEscrowGroup() {
        assertThat(NotificationCategory.DISPUTE_FILED_AGAINST_SELLER.getGroup())
                .isEqualTo(NotificationGroup.ESCROW);
    }

    @Test
    void disputeResolvedIsInEscrowGroup() {
        assertThat(NotificationCategory.DISPUTE_RESOLVED.getGroup())
                .isEqualTo(NotificationGroup.ESCROW);
    }

    @Test
    void reconciliationMismatchIsInAdminOpsGroup() {
        assertThat(NotificationCategory.RECONCILIATION_MISMATCH.getGroup())
                .isEqualTo(NotificationGroup.ADMIN_OPS);
    }

    @Test
    void withdrawalCompletedIsInAdminOpsGroup() {
        assertThat(NotificationCategory.WITHDRAWAL_COMPLETED.getGroup())
                .isEqualTo(NotificationGroup.ADMIN_OPS);
    }

    @Test
    void withdrawalFailedIsInAdminOpsGroup() {
        assertThat(NotificationCategory.WITHDRAWAL_FAILED.getGroup())
                .isEqualTo(NotificationGroup.ADMIN_OPS);
    }

    @Test
    void adminOpsGroupContainsExpectedCategories() {
        assertThat(NotificationGroup.ADMIN_OPS.categories())
                .contains(
                    NotificationCategory.RECONCILIATION_MISMATCH,
                    NotificationCategory.WITHDRAWAL_COMPLETED,
                    NotificationCategory.WITHDRAWAL_FAILED
                );
    }
}
