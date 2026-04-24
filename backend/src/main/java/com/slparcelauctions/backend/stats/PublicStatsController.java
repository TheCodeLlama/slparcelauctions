package com.slparcelauctions.backend.stats;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * Public stats endpoint (Epic 07 sub-spec 1 §5.4). Anonymous callers are
 * allowed by SecurityConfig. The response carries
 * {@code Cache-Control: max-age=60, public} so CDNs and browsers can
 * absorb most of the load — the 60s window matches the underlying
 * {@link PublicStatsCache} TTL.
 */
@RestController
@RequestMapping("/api/v1/stats")
@RequiredArgsConstructor
public class PublicStatsController {

    private final PublicStatsService service;

    @GetMapping("/public")
    public ResponseEntity<PublicStatsDto> getPublic() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(service.get());
    }
}
