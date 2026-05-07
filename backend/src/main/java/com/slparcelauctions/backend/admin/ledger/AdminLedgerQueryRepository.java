package com.slparcelauctions.backend.admin.ledger;

import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import com.slparcelauctions.backend.admin.ledger.dto.AdminLedgerFilterParams;
import com.slparcelauctions.backend.admin.ledger.dto.AdminLedgerKind;
import com.slparcelauctions.backend.admin.ledger.dto.AdminLedgerRowDto;
import com.slparcelauctions.backend.admin.ledger.exception.AdminLedgerStateException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

/**
 * Native-SQL reader for the admin global ledger view. Constructs a single
 * {@code SELECT} whose body is a {@code UNION ALL} of up to five sub-selects
 * — one per {@link AdminLedgerKind}. Only the kinds the admin selected get
 * compiled into the SQL; unselected arms are skipped at build time.
 *
 * <p>Each arm projects a 16-column shape: the 14 fields of
 * {@link AdminLedgerRowDto} plus three control columns
 * ({@code searchable_text}, {@code user_id}, {@code counterparty_user_id})
 * used by the outer WHERE clause for free-text search and "user matches
 * either side" filtering. The control columns do NOT make it into the DTO.
 *
 * <p><b>Sign convention:</b> {@code amount_lindens} is signed from the
 * resolved primary user's wallet POV — money leaving the user is negative,
 * money arriving is positive. Each arm encodes the sign rule in a {@code CASE}
 * expression. See spec §3.1 (table) for the full mapping per source.
 *
 * <p><b>BID_RESERVATION materializes two events per row:</b> one at
 * {@code created_at} (RESERVED) and a second at {@code released_at}
 * (RELEASED) when not null. Achieved via a UNION ALL nested inside the
 * BID_RESERVATION arm.
 */
@Repository
public class AdminLedgerQueryRepository {

    @PersistenceContext
    private EntityManager em;

    /** DTO sort property → SQL outer-alias column. Whitelist enforced. */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
        "createdAt",     "created_at",
        "amountLindens", "amount_lindens"
    );

    public Page<AdminLedgerRowDto> search(AdminLedgerFilterParams params, Pageable pageable) {
        Set<AdminLedgerKind> kinds = effectiveKinds(params.kinds());
        if (kinds.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        String unionBody = buildUnionBody(kinds);
        String whereClause = buildWhereClause(params);
        String orderBy = buildOrderBy(pageable.getSort());

        String selectSql = "SELECT * FROM (" + unionBody + ") events"
                + whereClause
                + orderBy
                + " LIMIT :limit OFFSET :offset";

        Query q = em.createNativeQuery(selectSql);
        bindParams(q, params);
        q.setParameter("limit", pageable.getPageSize());
        q.setParameter("offset", pageable.getOffset());

        @SuppressWarnings("unchecked")
        List<Object[]> raw = q.getResultList();
        List<AdminLedgerRowDto> rows = new ArrayList<>(raw.size());
        for (Object[] r : raw) {
            rows.add(mapRow(r));
        }

        long total = countMatching(unionBody, whereClause, params);
        return new PageImpl<>(rows, pageable, total);
    }

    private long countMatching(String unionBody, String whereClause, AdminLedgerFilterParams params) {
        String countSql = "SELECT COUNT(*) FROM (" + unionBody + ") events" + whereClause;
        Query c = em.createNativeQuery(countSql);
        bindParams(c, params);
        Object result = c.getSingleResult();
        if (result instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalStateException("Count query returned unexpected type: " + result);
    }

    private static Set<AdminLedgerKind> effectiveKinds(Set<AdminLedgerKind> requested) {
        if (requested == null || requested.isEmpty()) {
            return EnumSet.allOf(AdminLedgerKind.class);
        }
        return requested;
    }

    private String buildUnionBody(Set<AdminLedgerKind> kinds) {
        List<String> arms = new ArrayList<>(kinds.size());
        if (kinds.contains(AdminLedgerKind.USER_LEDGER))     arms.add(USER_LEDGER_ARM);
        if (kinds.contains(AdminLedgerKind.ESCROW_TXN))      arms.add(ESCROW_TXN_ARM);
        if (kinds.contains(AdminLedgerKind.TERMINAL_CMD))    arms.add(TERMINAL_CMD_ARM);
        if (kinds.contains(AdminLedgerKind.WITHDRAWAL))      arms.add(WITHDRAWAL_ARM);
        if (kinds.contains(AdminLedgerKind.BID_RESERVATION)) arms.add(BID_RESERVATION_ARM);
        return String.join(" UNION ALL ", arms);
    }

    private String buildWhereClause(AdminLedgerFilterParams params) {
        StringBuilder w = new StringBuilder(" WHERE 1=1 ");
        if (params.dateFrom() != null) w.append(" AND created_at >= :dateFrom ");
        if (params.dateTo() != null)   w.append(" AND created_at <  :dateTo ");
        if (params.userInternalId() != null) {
            w.append(" AND (user_id = :userId OR counterparty_user_id = :userId) ");
        }
        if (params.amountMin() != null) w.append(" AND ABS(amount_lindens) >= :amountMin ");
        if (params.amountMax() != null) w.append(" AND ABS(amount_lindens) <= :amountMax ");
        if (params.refType() != null)   w.append(" AND ref_type = :refType ");
        if (params.refId() != null)     w.append(" AND ref_id   = :refId ");
        if (params.entryType() != null) w.append(" AND entry_type = :entryType ");
        if (params.search() != null)    w.append(" AND searchable_text LIKE :search ");
        return w.toString();
    }

    private void bindParams(Query q, AdminLedgerFilterParams params) {
        if (params.dateFrom() != null)       q.setParameter("dateFrom", params.dateFrom());
        if (params.dateTo() != null)         q.setParameter("dateTo", params.dateTo());
        if (params.userInternalId() != null) q.setParameter("userId", params.userInternalId());
        if (params.amountMin() != null)      q.setParameter("amountMin", params.amountMin());
        if (params.amountMax() != null)      q.setParameter("amountMax", params.amountMax());
        if (params.refType() != null)        q.setParameter("refType", params.refType());
        if (params.refId() != null)          q.setParameter("refId", params.refId());
        if (params.entryType() != null)      q.setParameter("entryType", params.entryType());
        if (params.search() != null) {
            q.setParameter("search", "%" + params.search().toLowerCase() + "%");
        }
    }

    private String buildOrderBy(Sort sort) {
        String column = "created_at";
        String direction = "DESC";
        if (sort != null && sort.isSorted()) {
            Sort.Order primary = sort.iterator().next();
            String mapped = SORT_COLUMNS.get(primary.getProperty());
            if (mapped == null) {
                throw new AdminLedgerStateException(
                    "INVALID_SORT_COLUMN",
                    "Sort column '" + primary.getProperty() + "' is not allowed");
            }
            column = mapped;
            direction = primary.isAscending() ? "ASC" : "DESC";
        }
        // Composite tiebreaker for stable pagination.
        return " ORDER BY " + column + " " + direction + ", kind ASC, native_id ASC ";
    }

    private AdminLedgerRowDto mapRow(Object[] r) {
        AdminLedgerKind kind = AdminLedgerKind.valueOf((String) r[0]);
        Long nativeId = toLong(r[1]);
        return new AdminLedgerRowDto(
            kind,
            kind.name() + "-" + nativeId,
            nativeId,
            toOffsetDateTime(r[2]),
            (UUID) r[3],
            (String) r[4],
            (UUID) r[5],
            (String) r[6],
            toLong(r[7]),
            (String) r[8],
            (String) r[9],
            (String) r[10],
            toLong(r[11]),
            (String) r[12]
        );
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof BigInteger bi) return bi.longValueExact();
        if (o instanceof Number n) return n.longValue();
        throw new IllegalStateException("Cannot convert to Long: " + o.getClass());
    }

    private static OffsetDateTime toOffsetDateTime(Object o) {
        if (o == null) return null;
        if (o instanceof OffsetDateTime odt) return odt;
        if (o instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atOffset(ZoneOffset.UTC);
        }
        throw new IllegalStateException("Cannot convert to OffsetDateTime: " + o.getClass());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Per-source SELECTs. Each projects 13 DTO columns + 3 control columns
    // (searchable_text, user_id, counterparty_user_id) used by the outer
    // WHERE clause. Column order matches mapRow's index expectations.
    // ─────────────────────────────────────────────────────────────────────

    private static final String USER_LEDGER_ARM = """
        SELECT
          'USER_LEDGER'                                                AS kind,
          ule.id                                                       AS native_id,
          ule.created_at                                               AS created_at,
          u.public_id                                                  AS user_public_id,
          u.username                                                   AS username,
          NULL::uuid                                                   AS counterparty_public_id,
          NULL::text                                                   AS counterparty_username,
          CASE ule.entry_type
            WHEN 'DEPOSIT'             THEN  ule.amount
            WHEN 'WITHDRAW_QUEUED'     THEN -ule.amount
            WHEN 'WITHDRAW_COMPLETED'  THEN 0
            WHEN 'WITHDRAW_REVERSED'   THEN  ule.amount
            WHEN 'BID_RESERVED'        THEN -ule.amount
            WHEN 'BID_RELEASED'        THEN  ule.amount
            WHEN 'ESCROW_DEBIT'        THEN -ule.amount
            WHEN 'ESCROW_REFUND'       THEN  ule.amount
            WHEN 'LISTING_FEE_DEBIT'   THEN -ule.amount
            WHEN 'LISTING_FEE_REFUND'  THEN  ule.amount
            WHEN 'PENALTY_DEBIT'       THEN -ule.amount
            WHEN 'ADJUSTMENT'          THEN  ule.amount
            ELSE ule.amount
          END                                                          AS amount_lindens,
          ule.entry_type                                               AS entry_type,
          NULL::text                                                   AS status,
          ule.ref_type                                                 AS ref_type,
          ule.ref_id                                                   AS ref_id,
          ule.description                                              AS description,
          LOWER(COALESCE(ule.description, '')
                || ' ' || COALESCE(ule.sl_transaction_id, '')
                || ' ' || COALESCE(ule.idempotency_key, ''))           AS searchable_text,
          ule.user_id                                                  AS user_id,
          NULL::bigint                                                 AS counterparty_user_id
        FROM user_ledger ule
        JOIN users u ON u.id = ule.user_id
        """;

    private static final String ESCROW_TXN_ARM = """
        SELECT
          'ESCROW_TXN'                                                 AS kind,
          et.id                                                        AS native_id,
          et.created_at                                                AS created_at,
          CASE
            WHEN et.type IN ('AUCTION_ESCROW_PAYMENT','LISTING_FEE_PAYMENT','LISTING_PENALTY_PAYMENT')
              THEN payer_u.public_id
            WHEN et.type IN ('AUCTION_ESCROW_PAYOUT','AUCTION_ESCROW_REFUND','LISTING_FEE_REFUND')
              THEN payee_u.public_id
            ELSE NULL::uuid
          END                                                          AS user_public_id,
          CASE
            WHEN et.type IN ('AUCTION_ESCROW_PAYMENT','LISTING_FEE_PAYMENT','LISTING_PENALTY_PAYMENT')
              THEN payer_u.username
            WHEN et.type IN ('AUCTION_ESCROW_PAYOUT','AUCTION_ESCROW_REFUND','LISTING_FEE_REFUND')
              THEN payee_u.username
            ELSE NULL::text
          END                                                          AS username,
          CASE
            WHEN et.type IN ('AUCTION_ESCROW_PAYMENT','LISTING_FEE_PAYMENT','LISTING_PENALTY_PAYMENT')
              THEN payee_u.public_id
            WHEN et.type IN ('AUCTION_ESCROW_PAYOUT','AUCTION_ESCROW_REFUND','LISTING_FEE_REFUND')
              THEN payer_u.public_id
            ELSE NULL::uuid
          END                                                          AS counterparty_public_id,
          CASE
            WHEN et.type IN ('AUCTION_ESCROW_PAYMENT','LISTING_FEE_PAYMENT','LISTING_PENALTY_PAYMENT')
              THEN payee_u.username
            WHEN et.type IN ('AUCTION_ESCROW_PAYOUT','AUCTION_ESCROW_REFUND','LISTING_FEE_REFUND')
              THEN payer_u.username
            ELSE NULL::text
          END                                                          AS counterparty_username,
          CASE
            WHEN et.type IN ('AUCTION_ESCROW_PAYMENT','LISTING_FEE_PAYMENT','LISTING_PENALTY_PAYMENT')
              THEN -et.amount
            WHEN et.type IN ('AUCTION_ESCROW_PAYOUT','AUCTION_ESCROW_REFUND','LISTING_FEE_REFUND')
              THEN  et.amount
            ELSE et.amount
          END                                                          AS amount_lindens,
          et.type                                                      AS entry_type,
          et.status                                                    AS status,
          'ESCROW'                                                     AS ref_type,
          et.escrow_id                                                 AS ref_id,
          (et.type || ' escrow #' || et.escrow_id
            || ' (auction #' || es.auction_id || ')')                  AS description,
          LOWER(COALESCE(et.sl_transaction_id, '')
                || ' ' || COALESCE(et.terminal_id, '')
                || ' ' || COALESCE(et.error_message, ''))              AS searchable_text,
          CASE
            WHEN et.type IN ('AUCTION_ESCROW_PAYMENT','LISTING_FEE_PAYMENT','LISTING_PENALTY_PAYMENT')
              THEN payer_u.id
            WHEN et.type IN ('AUCTION_ESCROW_PAYOUT','AUCTION_ESCROW_REFUND','LISTING_FEE_REFUND')
              THEN payee_u.id
            ELSE NULL::bigint
          END                                                          AS user_id,
          CASE
            WHEN et.type IN ('AUCTION_ESCROW_PAYMENT','LISTING_FEE_PAYMENT','LISTING_PENALTY_PAYMENT')
              THEN payee_u.id
            WHEN et.type IN ('AUCTION_ESCROW_PAYOUT','AUCTION_ESCROW_REFUND','LISTING_FEE_REFUND')
              THEN payer_u.id
            ELSE NULL::bigint
          END                                                          AS counterparty_user_id
        FROM escrow_transactions et
        JOIN escrows es ON es.id = et.escrow_id
        LEFT JOIN users payer_u ON payer_u.sl_avatar_uuid = et.payer::uuid
        LEFT JOIN users payee_u ON payee_u.sl_avatar_uuid = et.payee::uuid
        """;

    private static final String TERMINAL_CMD_ARM = """
        SELECT
          'TERMINAL_CMD'                                               AS kind,
          tc.id                                                        AS native_id,
          tc.created_at                                                AS created_at,
          recip_u.public_id                                            AS user_public_id,
          recip_u.username                                             AS username,
          NULL::uuid                                                   AS counterparty_public_id,
          NULL::text                                                   AS counterparty_username,
          -tc.amount                                                   AS amount_lindens,
          tc.purpose                                                   AS entry_type,
          tc.status                                                    AS status,
          'TERMINAL_COMMAND'                                           AS ref_type,
          tc.id                                                        AS ref_id,
          (tc.purpose || ' → ' || COALESCE(recip_u.username, tc.recipient_uuid)
            || ' (status=' || tc.status || ', attempt ' || tc.attempt_count || ')') AS description,
          LOWER(COALESCE(tc.idempotency_key, '')
                || ' ' || COALESCE(tc.terminal_id, '')
                || ' ' || COALESCE(tc.last_error, '')
                || ' ' || COALESCE(tc.recipient_uuid, ''))             AS searchable_text,
          recip_u.id                                                   AS user_id,
          NULL::bigint                                                 AS counterparty_user_id
        FROM terminal_commands tc
        LEFT JOIN users recip_u ON recip_u.sl_avatar_uuid = tc.recipient_uuid::uuid
        """;

    private static final String WITHDRAWAL_ARM = """
        SELECT
          'WITHDRAWAL'                                                 AS kind,
          w.id                                                         AS native_id,
          w.created_at                                                 AS created_at,
          recip_u.public_id                                            AS user_public_id,
          recip_u.username                                             AS username,
          admin_u.public_id                                            AS counterparty_public_id,
          admin_u.username                                             AS counterparty_username,
          -w.amount                                                    AS amount_lindens,
          w.status                                                     AS entry_type,
          w.status                                                     AS status,
          'WITHDRAWAL'                                                 AS ref_type,
          w.id                                                         AS ref_id,
          ('Admin withdrawal → ' || COALESCE(recip_u.username, w.recipient_uuid)
            || ' (status=' || w.status || ')')                         AS description,
          LOWER(COALESCE(w.notes, '')
                || ' ' || COALESCE(w.failure_reason, '')
                || ' ' || COALESCE(w.recipient_uuid, ''))              AS searchable_text,
          recip_u.id                                                   AS user_id,
          admin_u.id                                                   AS counterparty_user_id
        FROM withdrawals w
        LEFT JOIN users recip_u ON recip_u.sl_avatar_uuid = w.recipient_uuid::uuid
        LEFT JOIN users admin_u ON admin_u.id = w.admin_user_id
        """;

    /**
     * BID_RESERVATION arm — produces TWO events per row: a RESERVED event at
     * created_at (always present) and a RELEASED event at released_at (only
     * when not null).
     */
    private static final String BID_RESERVATION_ARM = """
        SELECT
          'BID_RESERVATION'                                            AS kind,
          br.id                                                        AS native_id,
          br.created_at                                                AS created_at,
          u.public_id                                                  AS user_public_id,
          u.username                                                   AS username,
          NULL::uuid                                                   AS counterparty_public_id,
          NULL::text                                                   AS counterparty_username,
          -br.amount                                                   AS amount_lindens,
          'RESERVED'                                                   AS entry_type,
          'RESERVED'                                                   AS status,
          'BID'                                                        AS ref_type,
          br.bid_id                                                    AS ref_id,
          ('Bid reserved on auction #' || br.auction_id)               AS description,
          LOWER(CAST(br.id AS text)
                || ' ' || CAST(br.auction_id AS text)
                || ' ' || CAST(br.bid_id AS text))                     AS searchable_text,
          br.user_id                                                   AS user_id,
          NULL::bigint                                                 AS counterparty_user_id
        FROM bid_reservations br
        JOIN users u ON u.id = br.user_id
        UNION ALL
        SELECT
          'BID_RESERVATION'                                            AS kind,
          br.id                                                        AS native_id,
          br.released_at                                               AS created_at,
          u.public_id                                                  AS user_public_id,
          u.username                                                   AS username,
          NULL::uuid                                                   AS counterparty_public_id,
          NULL::text                                                   AS counterparty_username,
          br.amount                                                    AS amount_lindens,
          'RELEASED'                                                   AS entry_type,
          'RELEASED'                                                   AS status,
          'BID'                                                        AS ref_type,
          br.bid_id                                                    AS ref_id,
          ('Bid released (' || COALESCE(br.release_reason, 'unknown') || ')') AS description,
          LOWER(COALESCE(br.release_reason, ''))                       AS searchable_text,
          br.user_id                                                   AS user_id,
          NULL::bigint                                                 AS counterparty_user_id
        FROM bid_reservations br
        JOIN users u ON u.id = br.user_id
        WHERE br.released_at IS NOT NULL
        """;
}
