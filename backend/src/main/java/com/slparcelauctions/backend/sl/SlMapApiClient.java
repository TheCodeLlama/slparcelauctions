package com.slparcelauctions.backend.sl;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.slparcelauctions.backend.sl.dto.GridCoordinates;
import com.slparcelauctions.backend.sl.exception.ExternalApiTimeoutException;
import com.slparcelauctions.backend.sl.exception.RegionNotFoundException;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Resolves a region name to (grid_x, grid_y) via the SL Map CAP endpoint.
 * Response is JavaScript-ish: {@code coords[0] = x; coords[1] = y;}.
 */
@Component
@Slf4j
public class SlMapApiClient {

    private static final Pattern COORD_PATTERN = Pattern.compile(
            "coords\\[(\\d+)\\]\\s*=\\s*([\\d.]+)");

    private final WebClient webClient;
    private final String capUuid;
    private final int retryAttempts;
    private final long retryBackoffMs;

    @Autowired
    public SlMapApiClient(
            @Qualifier("slMapApiWebClient") WebClient webClient,
            @Value("${slpa.map-api.cap-uuid}") String capUuid,
            @Value("${slpa.map-api.retry-attempts:3}") int retryAttempts,
            @Value("${slpa.map-api.retry-backoff-ms:500}") long retryBackoffMs) {
        this.webClient = webClient;
        this.capUuid = capUuid;
        this.retryAttempts = retryAttempts;
        this.retryBackoffMs = retryBackoffMs;
    }

    public Mono<GridCoordinates> resolveRegion(String regionName) {
        log.debug("Resolving region {} via Map API", regionName);
        String body = "var=" + URLEncoder.encode(regionName, StandardCharsets.UTF_8);
        return webClient.post()
                .uri("/cap/0/{cap}", capUuid)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromValue(body))
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(Retry.backoff(retryAttempts, Duration.ofMillis(retryBackoffMs))
                        .filter(this::isTransient))
                .map(response -> parse(regionName, response))
                .onErrorMap(
                        throwable -> !(throwable instanceof ExternalApiTimeoutException)
                                && !(throwable instanceof RegionNotFoundException),
                        throwable -> new ExternalApiTimeoutException("Map", throwable.getMessage()));
    }

    private boolean isTransient(Throwable t) {
        if (t instanceof RegionNotFoundException) {
            // Parse-failure / region-does-not-exist is a client error; never retry.
            return false;
        }
        if (t instanceof WebClientResponseException e) {
            return e.getStatusCode().is5xxServerError();
        }
        // Network-level exceptions (connect timeout, read timeout) are also transient
        return true;
    }

    private GridCoordinates parse(String regionName, String response) {
        Double x = null;
        Double y = null;
        Matcher m = COORD_PATTERN.matcher(response);
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            double val = Double.parseDouble(m.group(2));
            if (idx == 0) x = val;
            if (idx == 1) y = val;
        }
        if (x == null || y == null) {
            log.warn("Map API returned response with no coords for region {}", regionName);
            throw new RegionNotFoundException(regionName);
        }
        return new GridCoordinates(x, y);
    }
}
