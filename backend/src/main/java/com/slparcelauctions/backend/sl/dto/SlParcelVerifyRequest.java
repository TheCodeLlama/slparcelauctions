package com.slparcelauctions.backend.sl.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code POST /api/v1/sl/parcel/verify}. Posted by an
 * in-world LSL rezzable verification object once it is rezzed on the parcel
 * the seller is trying to auction. The LSL script reads the parcel+owner via
 * {@code llGetParcelDetails} / {@code llGetLandOwnerAt} and the rezzed
 * position via {@code llGetPos}, then POSTs those fields with the seller's
 * 6-digit verification code.
 *
 * <p>The SL grid injects the {@code X-SecondLife-Shard} and
 * {@code X-SecondLife-Owner-Key} headers on the outbound {@code llHTTPRequest};
 * the controller validates them via {@link com.slparcelauctions.backend.sl.SlHeaderValidator}
 * as the trust boundary (the endpoint is {@code permitAll} at the HTTP layer).
 */
public record SlParcelVerifyRequest(
        @NotBlank @Pattern(regexp = "^[0-9]{6}$") String verificationCode,
        @NotNull UUID parcelUuid,
        @NotNull UUID ownerUuid,
        String parcelName,
        Integer areaSqm,
        String description,
        Integer primCapacity,
        Double regionPosX,
        Double regionPosY,
        Double regionPosZ) {
}
