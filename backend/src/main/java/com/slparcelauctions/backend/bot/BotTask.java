package com.slparcelauctions.backend.bot;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.common.BaseMutableEntity;
import com.slparcelauctions.backend.escrow.Escrow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Bot worker task row. After the ownership-only verification refactor
 * (spec 2026-05-16) the three task types VERIFY / MONITOR_AUCTION /
 * MONITOR_ESCROW are retired -- World API drives all of those flows.
 * The entity and table stay in place as future-extension scaffolding;
 * new task types can plug in by adding values to {@link BotTaskType}
 * without re-deriving the entity mapping. No production caller currently
 * persists a row.
 */
@Entity
@Table(name = "bot_tasks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class BotTask extends BaseMutableEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 20)
    private BotTaskType taskType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BotTaskStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    /** Historical: was set for MONITOR_ESCROW rows. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "escrow_id")
    private Escrow escrow;

    @Column(name = "parcel_uuid", nullable = false)
    private UUID parcelUuid;

    @Column(name = "region_name", length = 100)
    private String regionName;

    @Column(name = "position_x")
    private Double positionX;

    @Column(name = "position_y")
    private Double positionY;

    @Column(name = "position_z")
    private Double positionZ;

    @Column(name = "sentinel_price", nullable = false)
    private Long sentinelPrice;

    /** Historical: was set for MONITOR_AUCTION rows. */
    @Column(name = "expected_owner_uuid")
    private UUID expectedOwnerUuid;

    /** Historical: was set for MONITOR_ESCROW rows. */
    @Column(name = "expected_winner_uuid")
    private UUID expectedWinnerUuid;

    /** Historical: was set for MONITOR_ESCROW rows. */
    @Column(name = "expected_seller_uuid")
    private UUID expectedSellerUuid;

    /** Historical: was set for MONITOR_ESCROW rows. */
    @Column(name = "expected_max_sale_price_lindens")
    private Long expectedMaxSalePriceLindens;

    @Column(name = "next_run_at")
    private OffsetDateTime nextRunAt;

    @Column(name = "recurrence_interval_seconds")
    private Integer recurrenceIntervalSeconds;

    @Column(name = "assigned_bot_uuid")
    private UUID assignedBotUuid;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_data", columnDefinition = "jsonb")
    private Map<String, Object> resultData;

    @Column(name = "last_check_at")
    private OffsetDateTime lastCheckAt;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;
}
