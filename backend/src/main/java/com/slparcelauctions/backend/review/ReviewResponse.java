package com.slparcelauctions.backend.review;

import com.slparcelauctions.backend.common.BaseMutableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * One-time reviewee response to a {@link Review} (Epic 08 sub-spec 1
 * §3.2). The {@code review_id} FK is unique, so the constraint enforces
 * "one response per review" at the DB level. Creation is gated in
 * Task 3 via {@code POST /api/v1/reviews/{id}/respond} to the review's
 * {@code reviewee}.
 */
@Entity
@Table(name = "review_responses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class ReviewResponse extends BaseMutableEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false, unique = true)
    private Review review;

    @Column(nullable = false, length = 500)
    private String text;
}
