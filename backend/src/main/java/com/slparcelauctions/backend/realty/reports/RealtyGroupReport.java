package com.slparcelauctions.backend.realty.reports;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.common.BaseMutableEntity;
import com.slparcelauctions.backend.realty.RealtyGroup;
import com.slparcelauctions.backend.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * User-submitted report against a realty group. Admins triage reports from the
 * admin moderation queue; resolution writes {@code resolvedByAdmin}, {@code resolvedAt},
 * and {@code resolutionNotes} and flips {@link #status} to {@code RESOLVED} or
 * {@code DISMISSED}.
 *
 * <p>A reporter may have at most one OPEN report per realty group (enforced by a partial
 * unique index in V28).
 *
 * <p>Sub-project F spec §4.2.
 */
@Entity
@Table(name = "realty_group_reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class RealtyGroupReport extends BaseMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "realty_group_id", nullable = false)
    private RealtyGroup realtyGroup;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_user_id", nullable = false)
    private User reporter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private RealtyGroupReportReason reason;

    @Column(nullable = false, columnDefinition = "text")
    private String details;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RealtyGroupReportStatus status = RealtyGroupReportStatus.OPEN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by_admin_id")
    private User resolvedByAdmin;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "resolution_notes", columnDefinition = "text")
    private String resolutionNotes;
}
