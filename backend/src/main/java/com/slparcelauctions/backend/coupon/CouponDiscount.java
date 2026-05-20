package com.slparcelauctions.backend.coupon;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One discount line on a {@link Coupon}'s bundle. A coupon's full
 * discount payload is the collection of these rows. Each line is a
 * {@code (target, op, value)} tuple; see {@link DiscountTarget} and
 * {@link DiscountOp} for semantics.
 *
 * <p>Plain {@code @Entity} with no UUID public id - this row is a
 * private child of its parent coupon, never exposed on its own URL.
 * FK lookups use the integer {@code id}.
 */
@Entity
@Table(name = "coupon_discounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CouponDiscount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coupon_id", nullable = false)
    @JsonIgnore
    private Coupon coupon;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DiscountTarget target;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DiscountOp op;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal value;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;
}
