package com.slparcelauctions.backend.escrow.review;

import java.time.OffsetDateTime;
import com.slparcelauctions.backend.common.BaseMutableEntity;
import com.slparcelauctions.backend.escrow.Escrow;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "escrow_manual_reviews")
@Getter @Setter @NoArgsConstructor @SuperBuilder
public class EscrowManualReview extends BaseMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "escrow_id", nullable = false)
    private Escrow escrow;

    @Column(name = "requested_by_user_id")
    private Long requestedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_role", nullable = false, length = 16)
    private ManualReviewRole requestedRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "step", nullable = false, length = 16)
    private ManualReviewStep step;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 32)
    private ManualReviewReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ManualReviewStatus status;

    @Column(name = "resolved_by_admin_id")
    private Long resolvedByAdminId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", length = 32)
    private ManualReviewResolution resolution;

    @Column(name = "admin_notes", columnDefinition = "text")
    private String adminNotes;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;
}
