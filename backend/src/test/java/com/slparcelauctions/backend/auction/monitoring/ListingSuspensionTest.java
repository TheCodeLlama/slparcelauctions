package com.slparcelauctions.backend.auction.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.realty.moderation.RealtyGroupSuspension;
import com.slparcelauctions.backend.realty.moderation.SuspensionReason;
import com.slparcelauctions.backend.user.User;

class ListingSuspensionTest {

    private static final OffsetDateTime T = OffsetDateTime.of(
            2026, 5, 12, 12, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void builder_assignsAllFields_forAdminGroupBulkRow() {
        User admin = User.builder().username("admin").email("a@x").build();
        RealtyGroupSuspension link = RealtyGroupSuspension.builder()
                .reason(SuspensionReason.FRAUD)
                .issuedAt(T)
                .build();
        UUID bulkActionId = UUID.randomUUID();

        ListingSuspension ls = ListingSuspension.builder()
                .cause(ListingSuspensionCause.ADMIN_GROUP_BULK)
                .suspendedByAdmin(admin)
                .groupSuspension(link)
                .bulkActionId(bulkActionId)
                .reason("group banned for fraud")
                .notes("bulk suspend")
                .suspendedAt(T)
                .build();

        assertThat(ls.getCause()).isEqualTo(ListingSuspensionCause.ADMIN_GROUP_BULK);
        assertThat(ls.getSuspendedByAdmin()).isSameAs(admin);
        assertThat(ls.getGroupSuspension()).isSameAs(link);
        assertThat(ls.getBulkActionId()).isEqualTo(bulkActionId);
        assertThat(ls.getReason()).isEqualTo("group banned for fraud");
        assertThat(ls.getNotes()).isEqualTo("bulk suspend");
        assertThat(ls.getSuspendedAt()).isEqualTo(T);
        assertThat(ls.getLiftedAt()).isNull();
        assertThat(ls.getCancelledAt()).isNull();
        assertThat(ls.getPublicId()).isNotNull();
    }

    @Test
    void builder_supportsAutoCauseWithoutAdmin() {
        ListingSuspension ls = ListingSuspension.builder()
                .cause(ListingSuspensionCause.AUTO_OWNERSHIP_CHANGE)
                .suspendedAt(T)
                .build();

        assertThat(ls.getCause()).isEqualTo(ListingSuspensionCause.AUTO_OWNERSHIP_CHANGE);
        assertThat(ls.getSuspendedByAdmin()).isNull();
        assertThat(ls.getGroupSuspension()).isNull();
        assertThat(ls.getBulkActionId()).isNull();
    }

    @Test
    void setters_supportLiftedAndCancelledTransitions() {
        ListingSuspension ls = ListingSuspension.builder()
                .cause(ListingSuspensionCause.ADMIN_INDIVIDUAL)
                .suspendedAt(T)
                .build();

        ls.setLiftedAt(T.plusDays(1));
        assertThat(ls.getLiftedAt()).isEqualTo(T.plusDays(1));

        ListingSuspension other = ListingSuspension.builder()
                .cause(ListingSuspensionCause.ADMIN_GROUP_BULK)
                .suspendedAt(T)
                .build();
        other.setCancelledAt(T.plusHours(48));
        assertThat(other.getCancelledAt()).isEqualTo(T.plusHours(48));
    }
}
