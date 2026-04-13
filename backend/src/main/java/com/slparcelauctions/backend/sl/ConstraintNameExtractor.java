package com.slparcelauctions.backend.sl;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Pulls the Postgres constraint name out of a {@link DataIntegrityViolationException}
 * by walking the cause chain to Hibernate's {@link ConstraintViolationException}.
 * Returns an empty string if the chain doesn't contain one - the caller falls
 * through to the 500 handler in that case.
 *
 * <p>Note: this uses {@code org.hibernate.exception.ConstraintViolationException},
 * NOT {@code jakarta.validation.ConstraintViolationException}. Both classes share
 * a name but represent very different things; the JPA bean-validation type does
 * not carry a constraint name and would never match.
 */
final class ConstraintNameExtractor {

    private ConstraintNameExtractor() {}

    static String extract(DataIntegrityViolationException e) {
        Throwable cursor = e;
        while (cursor != null) {
            if (cursor instanceof ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                return name == null ? "" : name;
            }
            cursor = cursor.getCause();
        }
        return "";
    }

    static boolean isAvatarUuidUniqueViolation(String constraintName) {
        // Hibernate/Postgres default for {@code @Column(unique=true)} on
        // {@code users.sl_avatar_uuid} is {@code users_sl_avatar_uuid_key}. Stay
        // flexible against future naming-strategy changes by matching both tokens
        // rather than the exact string. A constraint name that incidentally contains
        // "sl_avatar_uuid" but not "users" (e.g., a hypothetical
        // {@code bans_sl_avatar_uuid_idx}) will correctly NOT match.
        if (constraintName == null || constraintName.isEmpty()) {
            return false;
        }
        return constraintName.contains("users") && constraintName.contains("sl_avatar_uuid");
    }
}
