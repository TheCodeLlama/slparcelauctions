package com.slparcelauctions.backend.realty.exception;

import java.util.UUID;

import lombok.Getter;

@Getter
public class RealtyGroupNotFoundException extends RuntimeException {
    private final UUID publicId;

    public RealtyGroupNotFoundException(UUID publicId) {
        super("Realty group not found: " + publicId);
        this.publicId = publicId;
    }

    public RealtyGroupNotFoundException(String slug) {
        super("Realty group not found: slug=" + slug);
        this.publicId = null;
    }
}
