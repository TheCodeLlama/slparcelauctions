package com.slparcelauctions.backend.bot;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.escrow.Escrow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "bot_tasks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BotTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 20)
    private BotTaskType taskType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BotTaskStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    /** Set for MONITOR_ESCROW rows; null for VERIFY and MONITOR_AUCTION. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "escrow_id")
    private Escrow escrow;

    @Column(name = "parcel_uuid", nullable = false)
    private UUID parcelUuid;

    @Column(name = "region_name", length = 100)
    private String regionName;

    /** Denormalized from Parcel at task creation so the worker does not need to look up. */
    @Column(name = "position_x")
    private Double positionX;

    @Column(name = "position_y")
    private Double positionY;

    @Column(name = "position_z")
    private Double positionZ;

    @Column(name = "sentinel_price", nullable = false)
    private Long sentinelPrice;

    /** MONITOR_AUCTION: expected parcel owner at each check. */
    @Column(name = "expected_owner_uuid")
    private UUID expectedOwnerUuid;

    /** MONITOR_AUCTION: expected AuthBuyerID (normally the primary escrow UUID). */
    @Column(name = "expected_auth_buyer_uuid")
    private UUID expectedAuthBuyerUuid;

    /** MONITOR_AUCTION: expected SalePrice (normally the sentinel). */
    @Column(name = "expected_sale_price_lindens")
    private Long expectedSalePriceLindens;

    /** MONITOR_ESCROW: expected winner SL UUID (signals TRANSFER_COMPLETE when observed owner). */
    @Column(name = "expected_winner_uuid")
    private UUID expectedWinnerUuid;

    /** MONITOR_ESCROW: expected seller SL UUID (normal STILL_WAITING state). */
    @Column(name = "expected_seller_uuid")
    private UUID expectedSellerUuid;

    /**
     * MONITOR_ESCROW: SalePrice threshold below which TRANSFER_READY fires
     * (in addition to the AuthBuyerID-matches-winner condition). Default 1
     * tolerates sellers who set L$1 by habit. See FOOTGUNS and spec §4.6.
     */
    @Column(name = "expected_max_sale_price_lindens")
    private Long expectedMaxSalePriceLindens;

    /** MONITOR_*: scheduled time for the next check. Null for VERIFY. */
    @Column(name = "next_run_at")
    private OffsetDateTime nextRunAt;

    /** MONITOR_*: interval between checks; null for VERIFY. */
    @Column(name = "recurrence_interval_seconds")
    private Integer recurrenceIntervalSeconds;

    @Column(name = "assigned_bot_uuid")
    private UUID assignedBotUuid;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_data", columnDefinition = "jsonb")
    private Map<String, Object> resultData;

    /** Stamped on every monitor callback. Null for VERIFY. */
    @Column(name = "last_check_at")
    private OffsetDateTime lastCheckAt;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Set on terminal states only (COMPLETED / FAILED / CANCELLED). */
    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @UpdateTimestamp
    @Column(name = "last_updated_at", nullable = false)
    private OffsetDateTime lastUpdatedAt;
}
