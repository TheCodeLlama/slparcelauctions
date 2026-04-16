package com.slparcelauctions.backend.auction;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.slparcelauctions.backend.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "cancellation_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CancellationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    @Column(name = "cancelled_from_status", nullable = false, length = 30)
    private String cancelledFromStatus;

    @Builder.Default
    @Column(name = "had_bids", nullable = false)
    private Boolean hadBids = false;

    @Column(length = 500)
    private String reason;

    @CreationTimestamp
    @Column(name = "cancelled_at", nullable = false, updatable = false)
    private OffsetDateTime cancelledAt;
}
