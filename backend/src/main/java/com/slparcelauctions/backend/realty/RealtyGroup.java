package com.slparcelauctions.backend.realty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.slparcelauctions.backend.common.BaseMutableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Realty Group entity — represents a brokerage-style group on SLParcels.
 *
 * <p>A group has one transferable leader seat ({@link #leaderId}) and any number of agents
 * (rows in {@code realty_group_members}). The leader holds every permission implicitly;
 * agents' capabilities are governed by their per-(group, user) permissions array on the
 * membership row.
 *
 * <p>Soft-deleted via {@link #dissolvedAt}: a dissolved group is invisible to public reads
 * and cannot be mutated, but the row is preserved for audit. The partial unique indexes on
 * {@code name_lower} and {@code slug} (WHERE {@code dissolved_at IS NULL}) make the
 * name/slug immediately reusable upon dissolution.
 *
 * @see com.slparcelauctions.backend.realty.permission.RealtyGroupPermission
 */
@Entity
@Table(name = "realty_groups")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class RealtyGroup extends BaseMutableEntity {

    @Column(nullable = false, length = 64)
    private String name;

    // Note: the DB also has a `name_lower CITEXT` generated column carrying
    // `lower(name)`, indexed UNIQUE WHERE dissolved_at IS NULL for case-insensitive
    // name uniqueness. We intentionally do NOT map it as a Java field — Hibernate's
    // schema validator can't reconcile the CITEXT column type with a String field, and
    // application code uses the LOWER()-based JPQL query in the repository for
    // case-insensitive lookups. The column exists only for the partial unique index.

    @Column(nullable = false, length = 80)
    private String slug;

    @Column(name = "leader_id", nullable = false)
    private Long leaderId;

    @Column(name = "logo_object_key", length = 500)
    private String logoObjectKey;

    @Column(name = "logo_content_type", length = 100)
    private String logoContentType;

    @Column(name = "logo_size_bytes")
    private Long logoSizeBytes;

    @Column(name = "cover_object_key", length = 500)
    private String coverObjectKey;

    @Column(name = "cover_content_type", length = 100)
    private String coverContentType;

    @Column(name = "cover_size_bytes")
    private Long coverSizeBytes;

    @Column(columnDefinition = "text")
    private String description;

    @Column(columnDefinition = "text")
    private String website;

    @Builder.Default
    @Column(name = "agent_fee_rate", precision = 5, scale = 4, nullable = false)
    private BigDecimal agentFeeRate = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "agent_fee_split", precision = 5, scale = 4, nullable = false)
    private BigDecimal agentFeeSplit = new BigDecimal("0.5000");

    @Builder.Default
    @Column(name = "member_seat_limit", nullable = false)
    private Integer memberSeatLimit = 50;

    /**
     * Stamped at the end of a leader-driven rename. NULL means the current name is the
     * original (never renamed). The 30-day non-admin rename cooldown is computed off this
     * column. Admin renames intentionally do not bump this value so the leader is not
     * punished by an admin-initiated rename.
     */
    @Column(name = "last_renamed_at")
    private OffsetDateTime lastRenamedAt;

    /**
     * Soft-delete marker. Once set, the group is invisible to public/listing surfaces and
     * cannot be mutated. The row stays for audit; the partial unique indexes immediately
     * allow a new group to claim the same name+slug.
     */
    @Column(name = "dissolved_at")
    private OffsetDateTime dissolvedAt;

    public boolean isDissolved() {
        return dissolvedAt != null;
    }
}
