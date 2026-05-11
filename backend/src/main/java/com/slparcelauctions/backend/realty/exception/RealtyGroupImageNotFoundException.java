package com.slparcelauctions.backend.realty.exception;

import java.util.UUID;

import lombok.Getter;

/**
 * Raised when a public {@code GET .../logo/image} or {@code GET .../cover/image} request
 * lands on a group that has never had a logo / cover uploaded. Maps to {@code 404
 * REALTY_GROUP_IMAGE_NOT_FOUND} via {@link RealtyExceptionHandler}.
 *
 * <p>Separate from {@link RealtyGroupNotFoundException} so the wire surface can
 * distinguish "the group itself does not exist" (404 {@code REALTY_GROUP_NOT_FOUND}) from
 * "the group exists but this kind of image is unset" (404 {@code
 * REALTY_GROUP_IMAGE_NOT_FOUND}).
 */
@Getter
public class RealtyGroupImageNotFoundException extends RuntimeException {

    public enum Kind { LOGO, COVER }

    private final UUID groupPublicId;
    private final Kind kind;

    public RealtyGroupImageNotFoundException(UUID groupPublicId, Kind kind) {
        super("Realty group " + groupPublicId + " has no " + kind.name().toLowerCase() + " set");
        this.groupPublicId = groupPublicId;
        this.kind = kind;
    }
}
