package com.slparcelauctions.backend.realty.exception;

import com.slparcelauctions.backend.realty.permission.RealtyGroupPermission;

import lombok.Getter;

@Getter
public class RealtyGroupPermissionDeniedException extends RuntimeException {
    private final RealtyGroupPermission missingPermission;

    public RealtyGroupPermissionDeniedException(RealtyGroupPermission missing) {
        super("Missing realty group permission: " + missing.name());
        this.missingPermission = missing;
    }

    public RealtyGroupPermissionDeniedException(String message) {
        super(message);
        this.missingPermission = null;
    }
}
