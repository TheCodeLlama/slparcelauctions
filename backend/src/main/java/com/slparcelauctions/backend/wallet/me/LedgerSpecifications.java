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

    public static Specification<UserLedgerEntry> forUser(Long userId, LedgerFilter filter) {
        return (root, q, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("userId"), userId));
            if (filter.entryTypes() != null && !filter.entryTypes().isEmpty()) {
                predicates.add(root.get("entryType").in(filter.entryTypes()));
            }
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), filter.to()));
            }
            if (filter.amountMin() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("amount"), filter.amountMin()));
            }
            if (filter.amountMax() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("amount"), filter.amountMax()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
