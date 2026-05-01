package com.slparcelauctions.backend.wallet.me;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.slparcelauctions.backend.wallet.LedgerRow;
import com.slparcelauctions.backend.wallet.UserLedgerEntry;
import com.slparcelauctions.backend.wallet.UserLedgerEntryType;
import com.slparcelauctions.backend.wallet.WithdrawalStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import lombok.RequiredArgsConstructor;

/**
 * Single-query collapsed ledger reader. The {@link LedgerCollapsedRepository}
 * fragment lives here so the query construction is shared between the
 * paginated controller path and the CSV streaming path — same WHERE clause,
 * same status CASE EXPR, same row shape.
 *
 * <p>The query:
 * <ul>
 *   <li>Excludes {@code WITHDRAW_COMPLETED} and {@code WITHDRAW_REVERSED}
 *       rows from the result entirely. They still exist in the DB for
 *       audit, but are never surfaced to the user.</li>
 *   <li>For each remaining row, computes a
 *       {@link WithdrawalStatus} via a {@code CASE} expression with
 *       {@code EXISTS} subqueries that check for paired sibling rows
 *       (matched on {@code refType='USER_LEDGER'} and {@code refId=q.id}).
 *       Status is null for entry types other than
 *       {@code WITHDRAW_QUEUED}.</li>
 *   <li>Applies {@link LedgerFilter} via the same predicates that the
 *       Specification path used.</li>
 *   <li>Sorts DESC by {@code createdAt}.</li>
 * </ul>
 *
 * <p>One SELECT for the page rows + one SELECT COUNT for the total. The
 * EXISTS subqueries use the index-supported {@code (user_id, ref_type,
 * ref_id)} predicate path, so they stay cheap even on a large ledger.
 */
@RequiredArgsConstructor
public class LedgerCollapsedRepositoryImpl implements LedgerCollapsedRepository {

    private final EntityManager em;

    @Override
    public Page<LedgerRow> findCollapsedForUser(
            Long userId, LedgerFilter filter, Pageable pageable) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> q = cb.createTupleQuery();
        Root<UserLedgerEntry> root = q.from(UserLedgerEntry.class);

        Expression<String> statusExpr = buildStatusExpression(cb, q, root);
        q.multiselect(root.alias("entry"), statusExpr.alias("status"))
                .where(buildPredicates(cb, root, userId, filter))
                .orderBy(cb.desc(root.get("createdAt")));

        TypedQuery<Tuple> tq = em.createQuery(q);
        tq.setFirstResult((int) pageable.getOffset());
        tq.setMaxResults(pageable.getPageSize());

        // getResultList materializes the page eagerly; safe to use outside
        // a long-lived session. Stream-then-collect would close the JDBC
        // ResultSet between rows when no transaction is active.
        List<LedgerRow> rows = tq.getResultList().stream()
                .map(LedgerCollapsedRepositoryImpl::tupleToRow)
                .toList();

        long total = countMatching(cb, userId, filter);
        return new PageImpl<>(rows, pageable, total);
    }

    @Override
    public Stream<LedgerRow> streamCollapsedForUser(Long userId, LedgerFilter filter) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> q = cb.createTupleQuery();
        Root<UserLedgerEntry> root = q.from(UserLedgerEntry.class);

        Expression<String> statusExpr = buildStatusExpression(cb, q, root);
        q.multiselect(root.alias("entry"), statusExpr.alias("status"))
                .where(buildPredicates(cb, root, userId, filter))
                .orderBy(cb.desc(root.get("createdAt")));

        return em.createQuery(q).getResultStream()
                .map(LedgerCollapsedRepositoryImpl::tupleToRow);
    }

    private long countMatching(CriteriaBuilder cb, Long userId, LedgerFilter filter) {
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<UserLedgerEntry> cRoot = cq.from(UserLedgerEntry.class);
        cq.select(cb.count(cRoot))
                .where(buildPredicates(cb, cRoot, userId, filter));
        return em.createQuery(cq).getSingleResult();
    }

    /**
     * Predicate set shared by the page query, the streaming query, and the
     * count query. Always scopes to the given userId, always excludes the
     * two terminal withdrawal entry types, then applies the user filter.
     */
    private Predicate[] buildPredicates(
            CriteriaBuilder cb, Root<UserLedgerEntry> root,
            Long userId, LedgerFilter filter) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(root.get("userId"), userId));
        // Hide the terminal rows — the WITHDRAW_QUEUED row is the canonical
        // user-facing row; status is computed via the EXISTS subqueries.
        predicates.add(cb.not(root.get("entryType").in(
                UserLedgerEntryType.WITHDRAW_COMPLETED,
                UserLedgerEntryType.WITHDRAW_REVERSED)));
        if (filter != null) {
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
        }
        return predicates.toArray(new Predicate[0]);
    }

    /**
     * Builds the {@code CASE WHEN ... ELSE NULL END} expression yielding
     * one of {@code "COMPLETED"}, {@code "REVERSED"}, {@code "PENDING"}
     * for {@code WITHDRAW_QUEUED} rows, and SQL NULL for any other entry
     * type.
     */
    private Expression<String> buildStatusExpression(
            CriteriaBuilder cb, CriteriaQuery<?> q, Root<UserLedgerEntry> root) {
        Subquery<Long> hasCompleted = existsSibling(cb, q, root, UserLedgerEntryType.WITHDRAW_COMPLETED);
        Subquery<Long> hasReversed = existsSibling(cb, q, root, UserLedgerEntryType.WITHDRAW_REVERSED);
        return cb.<String>selectCase()
                .when(cb.notEqual(root.get("entryType"), UserLedgerEntryType.WITHDRAW_QUEUED),
                        cb.<String>nullLiteral(String.class))
                .when(cb.exists(hasCompleted), WithdrawalStatus.COMPLETED.name())
                .when(cb.exists(hasReversed), WithdrawalStatus.REVERSED.name())
                .otherwise(WithdrawalStatus.PENDING.name());
    }

    /**
     * Subquery: a sibling row exists with the given terminal entry type
     * and {@code refType='USER_LEDGER', refId=q.id}.
     */
    private Subquery<Long> existsSibling(
            CriteriaBuilder cb, CriteriaQuery<?> q,
            Root<UserLedgerEntry> queuedRoot, UserLedgerEntryType siblingType) {
        Subquery<Long> sub = q.subquery(Long.class);
        Root<UserLedgerEntry> sib = sub.from(UserLedgerEntry.class);
        sub.select(cb.literal(1L))
                .where(cb.and(
                        cb.equal(sib.get("userId"), queuedRoot.get("userId")),
                        cb.equal(sib.get("refType"), "USER_LEDGER"),
                        cb.equal(sib.get("refId"), queuedRoot.get("id")),
                        cb.equal(sib.get("entryType"), siblingType)));
        return sub;
    }

    private static LedgerRow tupleToRow(Tuple t) {
        UserLedgerEntry entry = t.get("entry", UserLedgerEntry.class);
        String status = t.get("status", String.class);
        return new LedgerRow(entry,
                status == null ? null : WithdrawalStatus.valueOf(status));
    }
}
