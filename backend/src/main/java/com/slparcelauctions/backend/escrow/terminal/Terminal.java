package com.slparcelauctions.backend.escrow.terminal;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Registered in-world terminal (spec §7.5). terminal_id is the client-chosen
 * identifier (typically the SL object UUID) — used as the primary key rather
 * than a synthetic bigint because the terminal POSTs it back on every
 * command and callback.
 */
@Entity
@Table(name = "terminals",
        indexes = {
                @Index(name = "ix_terminals_active_last_seen", columnList = "active, last_seen_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Terminal {

    @Id
    @Column(name = "terminal_id", length = 100)
    private String terminalId;

    @Column(name = "http_in_url", nullable = false, length = 500)
    private String httpInUrl;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "region_name", length = 100)
    private String regionName;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @CreationTimestamp
    @Column(name = "registered_at", nullable = false, updatable = false)
    private OffsetDateTime registeredAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "last_heartbeat_at")
    private OffsetDateTime lastHeartbeatAt;

    @Column(name = "last_reported_balance")
    private Long lastReportedBalance;
}
