package com.slparcelauctions.backend.realty.analytics;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.realty.RealtyGroupRepository;
import com.slparcelauctions.backend.realty.analytics.dto.MemberCommissionRowDto;
import com.slparcelauctions.backend.realty.auth.RealtyGroupAuthorizer;
import com.slparcelauctions.backend.realty.exception.RealtyGroupNotFoundException;
import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;

/**
 * Read-only analytics over each member's lifetime + last-30-day
 * {@code AGENT_COMMISSION_CREDIT} totals (spec §6.8 / §15.2).
 *
 * <p>Single native query (Postgres-specific {@code FILTER (WHERE ...)}, {@code INTERVAL},
 * and a correlated {@code EXISTS} stitching {@code AGCOMM-{auctionId}} idempotency keys
 * to either the case-1 {@code auctions.realty_group_id} or the case-3
 * {@code realty_group_sl_groups.realty_group_id} linkage). H2 does not implement
 * {@code FILTER}; tests must run against a real Postgres (the codebase's
 * {@code @SpringBootTest @ActiveProfiles("dev")} convention).
 *
 * <p>Authorization: caller must be the group's leader or hold the
 * {@link RealtyGroupPermission#MANAGE_MEMBERS MANAGE_MEMBERS} permission on their
 * membership row. The leader short-circuits inside
 * {@link RealtyGroupAuthorizer#assertCan}, so the assertion is the single auth surface.
 *
 * <p>Empty groups and groups with no qualifying ledger entries return well-formed lists
 * (one row per member with zeroed totals; empty list if there are no members), not errors.
 */
@Service
@RequiredArgsConstructor
public class GroupCommissionAnalyticsService {

    private final RealtyGroupRepository groupRepo;
    private final RealtyGroupAuthorizer authorizer;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public List<MemberCommissionRowDto> compute(UUID groupPublicId, Long callerUserId) {
        RealtyGroup group = groupRepo.findByPublicId(groupPublicId)
            .orElseThrow(() -> new RealtyGroupNotFoundException(groupPublicId));

        // Leader auto-passes inside assertCan via the authorizer's short-circuit.
        authorizer.assertCan(callerUserId, group.getId(), RealtyGroupPermission.MANAGE_MEMBERS);

        Query query = em.createNativeQuery("""
            SELECT u.public_id, u.display_name,
                   COALESCE(SUM(ul.amount), 0) AS lifetime,
                   COALESCE(SUM(ul.amount) FILTER (
                       WHERE ul.created_at > now() - INTERVAL '30 days'
                   ), 0) AS last_30_days
              FROM realty_group_members m
              JOIN users u ON u.id = m.user_id
              LEFT JOIN user_ledger ul
                ON ul.user_id = m.user_id
               AND ul.entry_type = 'AGENT_COMMISSION_CREDIT'
               AND ul.idempotency_key LIKE 'AGCOMM-%'
               AND EXISTS (
                 SELECT 1 FROM auctions a
                  WHERE 'AGCOMM-' || a.id = ul.idempotency_key
                    AND (a.realty_group_id = :groupId
                         OR EXISTS (
                           SELECT 1 FROM realty_group_sl_groups rsg
                            WHERE rsg.id = a.realty_group_sl_group_id
                              AND rsg.realty_group_id = :groupId
                         ))
               )
             WHERE m.group_id = :groupId
             GROUP BY u.public_id, u.display_name
             ORDER BY lifetime DESC
            """);
        query.setParameter("groupId", group.getId());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream()
            .map(r -> new MemberCommissionRowDto(
                (UUID) r[0],
                (String) r[1],
                ((Number) r[2]).longValue(),
                ((Number) r[3]).longValue()))
            .toList();
    }
}
