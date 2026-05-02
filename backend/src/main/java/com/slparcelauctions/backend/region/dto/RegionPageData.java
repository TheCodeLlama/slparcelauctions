package com.slparcelauctions.backend.region.dto;

import java.util.UUID;

/**
 * Typed result of parsing {@code world.secondlife.com/region/{uuid}}. Grid
 * coordinates are in region units (1 = 1 region = 256m); the Mainland check
 * inside {@code RegionService.upsert} multiplies by 256 before evaluating
 * the bounding-box table.
 *
 * <p>{@code maturityRaw} is the SL meta-tag value as returned (e.g. "PG_NOT",
 * "M_NOT", "AO_NOT"). Translation to canonical SLPA values
 * ({@code GENERAL}/{@code MODERATE}/{@code ADULT}) happens in the service layer
 * via {@code MaturityRating.fromSlCode}.
 */
public record RegionPageData(
        UUID slUuid,
        String name,
        Double gridX,
        Double gridY,
        String maturityRaw) {
}
