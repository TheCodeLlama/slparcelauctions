package com.slparcelauctions.backend.wallet.me;

import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.wallet.UserLedgerEntry;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

import lombok.RequiredArgsConstructor;

/**
 * Streams matching ledger entries for the given user + filter. Caller MUST
 * close the stream (try-with-resources) and MUST hold an active read-only
 * transaction. Sorted DESC by created_at.
 *
 * <p>Used by the CSV export endpoint so multi-thousand-row downloads don't
 * hold the full result set in memory.
 */
@Service
@RequiredArgsConstructor
public class LedgerStreamingService {

    private final EntityManager em;

    @Transactional(readOnly = true)
    public Stream<UserLedgerEntry> streamFiltered(Long userId, LedgerFilter filter) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<UserLedgerEntry> q = cb.createQuery(UserLedgerEntry.class);
        Root<UserLedgerEntry> root = q.from(UserLedgerEntry.class);
        q.select(root)
                .where(LedgerSpecifications.forUser(userId, filter)
                        .toPredicate(root, q, cb))
                .orderBy(cb.desc(root.get("createdAt")));
        return em.createQuery(q).getResultStream();
    }
}
