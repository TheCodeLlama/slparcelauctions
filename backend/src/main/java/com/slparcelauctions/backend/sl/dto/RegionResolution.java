package com.slparcelauctions.backend.sl.dto;

/**
 * Tri-state outcome of a region-name -> grid-coordinate lookup. The
 * caller distinguishes:
 *
 * <ul>
 *   <li>{@link Found}: lookup succeeded; carries the grid coords.</li>
 *   <li>{@link NotFound}: upstream unambiguously said "no such region"
 *       (e.g. HTTP 404 or empty payload). Safe to negative-cache.</li>
 *   <li>{@link UpstreamError}: transient or unknown failure (timeout,
 *       5xx, network). Must NOT be cached — the next caller retries.</li>
 * </ul>
 *
 * <p>Sealed so the {@link com.slparcelauctions.backend.sl.CachedRegionResolver}
 * can dispatch via an exhaustive {@code switch} without a default arm.
 */
public sealed interface RegionResolution
        permits RegionResolution.Found, RegionResolution.NotFound, RegionResolution.UpstreamError {

    record Found(double gridX, double gridY) implements RegionResolution {}

    record NotFound() implements RegionResolution {}

    record UpstreamError(String reason) implements RegionResolution {}
}
