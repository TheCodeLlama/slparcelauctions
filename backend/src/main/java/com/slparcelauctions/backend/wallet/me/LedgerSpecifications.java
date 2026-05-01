package com.slparcelauctions.backend.wallet.me;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import com.slparcelauctions.backend.wallet.UserLedgerEntry;

import jakarta.persistence.criteria.Predicate;

/**
 * JPA {@link Specification} factory for {@link UserLedgerEntry} queries
 * driven by {@link LedgerFilter}. Always scopes results to a single
 * {@code userId} — the controller passes the authenticated principal's id,
 * never client-supplied.
 */
public final class LedgerSpecifications {

    private LedgerSpecifications() {}

    public static Specification<UserLedgerEntry> forUser(Long userId, LedgerFilter f) {
        return (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("userId"), userId));
            if (f.entryTypes() != null && !f.entryTypes().isEmpty()) {
                ps.add(root.get("entryType").in(f.entryTypes()));
            }
            if (f.from() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), f.from()));
            }
            if (f.to() != null) {
                ps.add(cb.lessThan(root.get("createdAt"), f.to()));
            }
            if (f.amountMin() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("amount"), f.amountMin()));
            }
            if (f.amountMax() != null) {
                ps.add(cb.lessThanOrEqualTo(root.get("amount"), f.amountMax()));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }
}
