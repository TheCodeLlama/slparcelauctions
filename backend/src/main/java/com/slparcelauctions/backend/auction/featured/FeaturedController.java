package com.slparcelauctions.backend.auction.featured;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * Three public homepage rails. Anonymous access — gated in
 * {@code SecurityConfig} as {@code GET /api/v1/auctions/rails/**}.
 *
 * <p>The Redis-side {@link FeaturedCache} fronts the service layer; the
 * 60s {@code Cache-Control: public} response header is layered on top so
 * intermediate caches and the browser can avoid re-hitting the backend
 * within the same window.
 */
@RestController
@RequestMapping("/api/v1/auctions/rails")
@RequiredArgsConstructor
public class FeaturedController {

    private final FeaturedService service;

    @GetMapping("/featured")
    public ResponseEntity<FeaturedResponse> featured() {
        return cachedOk(service.get(FeaturedCategory.FEATURED));
    }

    @GetMapping("/ending-soon")
    public ResponseEntity<FeaturedResponse> endingSoon() {
        return cachedOk(service.get(FeaturedCategory.ENDING_SOON));
    }

    @GetMapping("/trending")
    public ResponseEntity<FeaturedResponse> trending() {
        return cachedOk(service.get(FeaturedCategory.TRENDING));
    }

    private static ResponseEntity<FeaturedResponse> cachedOk(FeaturedResponse body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(body);
    }
}
