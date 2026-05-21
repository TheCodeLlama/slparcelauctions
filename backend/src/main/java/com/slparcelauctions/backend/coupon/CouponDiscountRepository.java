package com.slparcelauctions.backend.coupon;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CouponDiscountRepository extends JpaRepository<CouponDiscount, Long> {
}
