package com.slparcelauctions.backend.admin.listings.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for the four admin listing mutations (warn / suspend / cancel /
 * reinstate). The {@code notes} field is BOTH the audit-log entry AND the
 * body of the seller-facing notification, so it must be written for the
 * seller's eyes — see the modal copy in the spec §7.
 */
public record AdminListingActionRequest(
    @NotBlank(message = "Notes are required")
    @Size(min = 5, max = 1000, message = "Notes must be between 5 and 1000 characters")
    String notes
) {}
