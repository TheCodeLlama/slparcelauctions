package com.slparcelauctions.backend.review;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.slparcelauctions.backend.user.User;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-user flag row against a {@link Review} (Epic 08 sub-spec 1 §3.3).
 * The {@code (review_id, flagger_id)} uniqueness constraint enforces
 * "one flag per user per review" at the DB level — the flag endpoint in
 * Task 3 checks this first so callers get a {@code 409
 * ReviewFlagAlreadyExistsException}, but the DB is the last line of
 * defence under concurrent flags.
 *
 * <p>{@code elaboration} is optional except when
 * {@code reason=OTHER} — that cross-field validation lives at the DTO
 * layer in Task 3, not on the entity.
 */
@Entity
@Table(name = "review_flags",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_review_flags_review_flagger",
                columnNames = {"review_id", "flagger_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false)
    private Review review;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flagger_id", nullable = false)
    private User flagger;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewFlagReason reason;

    @Column(length = 500)
    private String elaboration;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
