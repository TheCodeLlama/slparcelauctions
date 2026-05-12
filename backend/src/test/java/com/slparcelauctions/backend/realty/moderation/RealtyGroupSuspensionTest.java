package com.slparcelauctions.backend.realty.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.user.User;

class RealtyGroupSuspensionTest {

    private static final OffsetDateTime T = OffsetDateTime.of(
            2026, 5, 12, 12, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void builder_assignsAllFields() {
        RealtyGroup group = RealtyGroup.builder()
                .name("Acme Realty").slug("acme-realty").leaderId(1L).build();
        User admin = User.builder().username("admin").email("a@x").build();

        RealtyGroupSuspension s = RealtyGroupSuspension.builder()
                .realtyGroup(group)
                .issuedByAdmin(admin)
                .reason(SuspensionReason.FRAUD)
                .notes("fraud notes")
                .issuedAt(T)
                .expiresAt(T.plusDays(7))
                .build();

        assertThat(s.getRealtyGroup()).isSameAs(group);
        assertThat(s.getIssuedByAdmin()).isSameAs(admin);
        assertThat(s.getReason()).isEqualTo(SuspensionReason.FRAUD);
        assertThat(s.getNotes()).isEqualTo("fraud notes");
        assertThat(s.getIssuedAt()).isEqualTo(T);
        assertThat(s.getExpiresAt()).isEqualTo(T.plusDays(7));
        assertThat(s.getLiftedAt()).isNull();
        assertThat(s.getLiftedByAdmin()).isNull();
        assertThat(s.getPublicId()).isNotNull();
    }

    @Test
    void isActive_returnsTrueForTimedSuspensionBeforeExpiry() {
        RealtyGroupSuspension s = RealtyGroupSuspension.builder()
                .issuedAt(T)
                .expiresAt(T.plusDays(7))
                .reason(SuspensionReason.OTHER)
                .build();
        assertThat(s.isActive(T.plusDays(1))).isTrue();
    }

    @Test
    void isActive_returnsFalseAfterExpiry() {
        RealtyGroupSuspension s = RealtyGroupSuspension.builder()
                .issuedAt(T)
                .expiresAt(T.plusDays(7))
                .reason(SuspensionReason.OTHER)
                .build();
        assertThat(s.isActive(T.plusDays(8))).isFalse();
    }

    @Test
    void isActive_returnsTrueForPermanentBanIndefinitely() {
        RealtyGroupSuspension s = RealtyGroupSuspension.builder()
                .issuedAt(T)
                .expiresAt(null)
                .reason(SuspensionReason.TOS_VIOLATION)
                .build();
        assertThat(s.isActive(T.plusYears(10))).isTrue();
    }

    @Test
    void isActive_returnsFalseOnceLifted() {
        RealtyGroupSuspension s = RealtyGroupSuspension.builder()
                .issuedAt(T)
                .expiresAt(T.plusDays(7))
                .liftedAt(T.plusDays(1))
                .reason(SuspensionReason.OTHER)
                .build();
        assertThat(s.isActive(T.plusDays(2))).isFalse();
    }

    @Test
    void isActive_returnsFalseAtExactExpiryInstant() {
        // expiresAt.isAfter(now) is strict — equality returns false.
        RealtyGroupSuspension s = RealtyGroupSuspension.builder()
                .issuedAt(T)
                .expiresAt(T.plusDays(7))
                .reason(SuspensionReason.OTHER)
                .build();
        assertThat(s.isActive(T.plusDays(7))).isFalse();
    }
}
