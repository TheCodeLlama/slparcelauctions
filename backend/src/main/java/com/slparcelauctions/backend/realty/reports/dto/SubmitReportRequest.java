package com.slparcelauctions.backend.realty.reports.dto;

import com.slparcelauctions.backend.realty.reports.RealtyGroupReportReason;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/realty-groups/{publicId}/reports}.
 *
 * <p>Bean Validation constraints (enforced by {@code @Valid} on the controller):
 * <ul>
 *   <li>{@code reason} — must be one of the {@link RealtyGroupReportReason} enum values.
 *       Bound by Jackson's enum deserializer; an unknown string fails the JSON parse before
 *       reaching the controller and surfaces as 400 Bad Request.</li>
 *   <li>{@code details} — free-text justification; 10–2000 chars, non-blank. The lower
 *       bound deters drive-by reports with a single character; the upper bound mirrors the
 *       {@code listing_reports.details} column size so the two report surfaces share a
 *       consistent quality bar.</li>
 * </ul>
 *
 * <p>Sub-project F spec §6.1, §12.1.
 */
public record SubmitReportRequest(
    @NotNull RealtyGroupReportReason reason,
    @NotBlank @Size(min = 10, max = 2000) String details
) {}
