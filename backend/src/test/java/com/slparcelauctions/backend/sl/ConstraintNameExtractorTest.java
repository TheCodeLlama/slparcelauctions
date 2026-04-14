package com.slparcelauctions.backend.sl;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Direct unit tests for {@link ConstraintNameExtractor}. Locks in the cause-chain
 * walk + the tightened {@code isAvatarUuidUniqueViolation} matcher so a future
 * naming-strategy drift or a hypothetical unrelated constraint with overlapping
 * tokens cannot silently regress the SL race-path handler.
 */
class ConstraintNameExtractorTest {

    @Test
    void extract_findsHibernateConstraintNameFromCauseChain() {
        SQLException sql = new SQLException(
                "duplicate key value violates unique constraint \"users_sl_avatar_uuid_key\"");
        ConstraintViolationException hibernateEx =
                new ConstraintViolationException("duplicate", sql, "users_sl_avatar_uuid_key");
        DataIntegrityViolationException wrapper =
                new DataIntegrityViolationException("wrapped", hibernateEx);

        String name = ConstraintNameExtractor.extract(wrapper);

        assertThat(name).isEqualTo("users_sl_avatar_uuid_key");
    }

    @Test
    void extract_returnsEmptyWhenNoHibernateConstraintInChain() {
        DataIntegrityViolationException e = new DataIntegrityViolationException(
                "generic", new RuntimeException("not a constraint"));

        String name = ConstraintNameExtractor.extract(e);

        assertThat(name).isEmpty();
    }

    @Test
    void extract_handlesNullConstraintNameFromHibernateException() {
        ConstraintViolationException hibernateEx = new ConstraintViolationException(
                "duplicate", new SQLException("sql"), (String) null);
        DataIntegrityViolationException wrapper =
                new DataIntegrityViolationException("wrapped", hibernateEx);

        String name = ConstraintNameExtractor.extract(wrapper);

        assertThat(name).isEmpty();
    }

    @Test
    void isAvatarUuidUniqueViolation_matchesUsersConstraint() {
        assertThat(ConstraintNameExtractor.isAvatarUuidUniqueViolation("users_sl_avatar_uuid_key"))
                .isTrue();
    }

    @Test
    void isAvatarUuidUniqueViolation_matchesVariantNames() {
        // Hibernate naming-strategy drift: variants that include both "users" and
        // "sl_avatar_uuid" tokens should still match.
        assertThat(ConstraintNameExtractor.isAvatarUuidUniqueViolation("uk_users_sl_avatar_uuid"))
                .isTrue();
        // Constraint that incidentally contains "sl_avatar_uuid" but NOT "users" -
        // must not false-match, validating I.3's two-substring tightening.
        assertThat(ConstraintNameExtractor.isAvatarUuidUniqueViolation("bans_sl_avatar_uuid_idx"))
                .isFalse();
    }

    @Test
    void isAvatarUuidUniqueViolation_rejectsUnrelatedConstraints() {
        assertThat(ConstraintNameExtractor.isAvatarUuidUniqueViolation("users_email_key")).isFalse();
        assertThat(ConstraintNameExtractor.isAvatarUuidUniqueViolation("refresh_tokens_pkey")).isFalse();
        assertThat(ConstraintNameExtractor.isAvatarUuidUniqueViolation(null)).isFalse();
        assertThat(ConstraintNameExtractor.isAvatarUuidUniqueViolation("")).isFalse();
    }
}
