package com.slparcelauctions.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.slparcelauctions.backend.config.dto.ListingFeeConfigResponse;

/**
 * Public (unauthenticated) configuration endpoints.
 *
 * <p>Per Epic 03 sub-spec 2 §7.6, the frontend Activate step needs to render
 * the current listing-fee amount before the seller commits to paying — and
 * the unauth public browse pages want to show the same number as a
 * "platform cost" badge. Exposing a single, cached read endpoint avoids
 * duplicating the backend config in the frontend bundle.
 *
 * <p>The endpoint is permitted unauthenticated in {@code SecurityConfig}
 * ({@code GET /api/v1/config/listing-fee}). Only read-only public config
 * should live here; anything seller- or auction-scoped belongs on a
 * feature-owned controller.
 */
@RestController
@RequestMapping("/api/v1/config")
public class PublicConfigController {

    private final long listingFeeLindens;

    public PublicConfigController(
            @Value("${slpa.listing-fee.amount-lindens:100}") long listingFeeLindens) {
        this.listingFeeLindens = listingFeeLindens;
    }

    @GetMapping("/listing-fee")
    public ListingFeeConfigResponse listingFee() {
        return new ListingFeeConfigResponse(listingFeeLindens);
    }
}
