package com.slparcelauctions.backend.promotion;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.common.BaseMutableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One row per (auction x board assignment) representing a single PROMO-01
 * board-slot lease. Active iff {@code releasedAt IS NULL}. See spec section 4.
 */
@Entity
@Table(name = "featured_board_slots")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class FeaturedBoardSlot extends BaseMutableEntity {

    @Column(name = "board_index", nullable = false)
    private int boardIndex;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auction_id", nullable = false)
    @JsonIgnore
    private Auction auction;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "assigned_at", nullable = false)
    private OffsetDateTime assignedAt;

    @Column(name = "released_at")
    private OffsetDateTime releasedAt;
}
