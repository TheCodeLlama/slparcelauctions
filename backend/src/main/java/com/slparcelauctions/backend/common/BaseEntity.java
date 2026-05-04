package com.slparcelauctions.backend.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Top-level @MappedSuperclass. Every entity in the codebase extends this directly
 * (immutable / append-only) or {@link BaseMutableEntity} (mutable lifecycle).
 *
 * <p>Two-identifier model:
 * <ul>
 *   <li>{@code id} — internal {@code Long} primary key. Used for FK joins, internal lookups,
 *       admin internal-data endpoints, bot/LSL contracts, Postman variables. Annotated
 *       {@code @JsonIgnore} so it never escapes via direct entity serialization.</li>
 *   <li>{@code publicId} — random UUIDv4. Assigned at construction (not persist time) so
 *       equality and {@code HashSet} membership work pre-persist. The only identifier safe
 *       to expose anywhere a user can see it. JWT subject, REST DTOs, frontend types,
 *       WebSocket envelopes all use this.</li>
 * </ul>
 *
 * <p>{@link #equals} and {@link #hashCode} are {@code final}, keyed off {@code publicId}.
 * Subclasses must NOT override and must NOT use Lombok {@code @EqualsAndHashCode}.
 *
 * <p>Subclasses MUST use Lombok {@code @SuperBuilder}, not {@code @Builder} — plain
 * {@code @Builder} does not compose across {@code @MappedSuperclass} inheritance.
 *
 * <p>See {@code docs/superpowers/specs/2026-05-03-base-entity-uuid-design.md}.
 */
@MappedSuperclass
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonIgnore
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false,
            columnDefinition = "uuid")
    @Builder.Default
    @Setter(AccessLevel.NONE)
    private UUID publicId = UUID.randomUUID();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BaseEntity that)) return false;
        return publicId.equals(that.publicId);
    }

    @Override
    public final int hashCode() {
        return publicId.hashCode();
    }
}
