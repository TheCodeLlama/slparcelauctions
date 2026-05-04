package com.slparcelauctions.backend.admin.reports;

import java.time.OffsetDateTime;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.common.BaseMutableEntity;
import com.slparcelauctions.backend.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "listing_reports",
    uniqueConstraints = @UniqueConstraint(name = "uk_listing_reports_auction_reporter",
        columnNames = {"auction_id", "reporter_id"}),
    indexes = {
        @Index(name = "idx_listing_reports_status", columnList = "status, auction_id"),
        @Index(name = "idx_listing_reports_auction", columnList = "auction_id, updated_at DESC")
    })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @SuperBuilder
public class ListingReport extends BaseMutableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @Column(length = 100, nullable = false)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    private ListingReportReason reason;

    @Column(columnDefinition = "text", nullable = false)
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @Builder.Default
    private ListingReportStatus status = ListingReportStatus.OPEN;

    @Column(name = "admin_notes", columnDefinition = "text")
    private String adminNotes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;
}
