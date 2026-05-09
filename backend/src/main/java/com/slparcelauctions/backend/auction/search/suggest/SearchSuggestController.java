package com.slparcelauctions.backend.auction.search.suggest;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * Public typeahead endpoint for the header search overlay (spec
 * 2026-05-09-header-search-overlay-design §5.1). Returns at most 5
 * listings + 3 regions + a total-listings count, designed for the
 * popover row layout. Different latency / cardinality budget than
 * {@code /api/v1/auctions/search} — kept on its own path with its own
 * rate-limit bucket.
 */
@RestController
@RequestMapping("/api/v1/search/suggest")
@RequiredArgsConstructor
public class SearchSuggestController {

    private final SearchSuggestService service;

    @GetMapping
    public ResponseEntity<SuggestResponse> suggest(@RequestParam String q) {
        // Empty / short queries return an empty envelope without hitting
        // the DB. The frontend hook also gates on length>=2, but we
        // re-check because the contract is public.
        String trimmed = q == null ? "" : q.trim();
        if (trimmed.length() < 2) {
            return ResponseEntity.ok(SuggestResponse.empty());
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(15)).cachePublic())
                .body(service.suggest(trimmed));
    }
}
