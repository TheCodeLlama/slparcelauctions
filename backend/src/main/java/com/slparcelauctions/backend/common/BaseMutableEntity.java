package com.slparcelauctions.backend.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * @MappedSuperclass extending {@link BaseEntity} for entities with a mutable lifecycle —
 * rows that get UPDATE'd over time. Adds {@code updatedAt} (Hibernate
 * {@code @UpdateTimestamp}) and {@code version} ({@code @Version}, optimistic locking).
 *
 * <p>Append-only / immutable entities (e.g. {@code Bid}, {@code UserLedgerEntry},
 * {@code AdminAction}) extend {@link BaseEntity} directly, not this class.
 */
@MappedSuperclass
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseMutableEntity extends BaseEntity {

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;
}
