package com.slparcelauctions.backend.coupon;

import lombok.Getter;

/**
 * Domain exception thrown by {@link CouponService} for any coupon
 * admin-CRUD or redemption failure. The {@link #getCode() code} carries
 * the typed reason; the {@code message} carries optional human detail
 * (e.g. which immutable field was touched, which user public id was
 * unknown). The REST layer translates the code to an HTTP status +
 * stable string identifier.
 */
@Getter
public class CouponException extends RuntimeException {

    private final CouponRedemptionError code;

    public CouponException(CouponRedemptionError code) {
        super(code.name());
        this.code = code;
    }

    public CouponException(CouponRedemptionError code, String detail) {
        super(detail);
        this.code = code;
    }
}
