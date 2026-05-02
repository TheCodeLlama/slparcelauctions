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
import com.slparcelauctions.backend.sl.dto.RegionResolution;
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

    // SL CAP responses today look like {@code var <name> = {'x' : 1114, 'y' : 1034 };}.
    // The legacy {@code coords[N] = X} shape that earlier code parsed no longer
    // appears in the wild.
    private static final Pattern XY_PATTERN = Pattern.compile(
            "'x'\\s*:\\s*([\\d.]+)\\s*,\\s*'y'\\s*:\\s*([\\d.]+)");

    // Arbitrary JS identifier passed as the `var` query param. The CAP
    // endpoint echoes it as the variable name in its response body — the
    // value matters only insofar as it's a valid JS identifier; the parser
    // ignores it.
    private static final String VAR_NAME = "loc";

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

    /**
     * Synchronous tri-state lookup used by
     * {@link CachedRegionResolver}. Wraps {@link #resolveRegion} and
     * collapses its outcomes into a {@link RegionResolution} so the
     * caller never has to inspect raw {@code WebClient} exceptions:
     *
     * <ul>
     *   <li>upstream returned coords -> {@link RegionResolution.Found}</li>
     *   <li>parse-empty / 404 -> {@link RegionResolution.NotFound}</li>
     *   <li>5xx / timeout / network -> {@link RegionResolution.UpstreamError}</li>
     * </ul>
     *
     * <p>The blocking {@code .block()} is intentional: the search path is
     * already inside a Spring MVC servlet thread, and the resolver caches
     * positive hits for 7 days, so the call is cheap in steady state.
     */
    public RegionResolution resolve(String regionName) {
        try {
            GridCoordinates coords = resolveRegion(regionName).block();
            if (coords == null) {
                return new RegionResolution.NotFound();
            }
            return new RegionResolution.Found(coords.gridX(), coords.gridY());
        } catch (RegionNotFoundException e) {
            return new RegionResolution.NotFound();
        } catch (WebClientResponseException.NotFound e) {
            return new RegionResolution.NotFound();
        } catch (Exception e) {
            log.warn("Map API upstream error resolving '{}': {}", regionName, e.toString());
            return new RegionResolution.UpstreamError(e.toString());
        }
    }

    public Mono<GridCoordinates> resolveRegion(String regionName) {
        log.debug("Resolving region {} via Map API", regionName);
        String body = "var=" + URLEncoder.encode(VAR_NAME, StandardCharsets.UTF_8)
                + "&sim_name=" + URLEncoder.encode(regionName, StandardCharsets.UTF_8);
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
        Matcher m = XY_PATTERN.matcher(response);
        if (m.find()) {
            return new GridCoordinates(
                    Double.parseDouble(m.group(1)),
                    Double.parseDouble(m.group(2)));
        }
        log.warn("Map API returned response with no coords for region {}", regionName);
        throw new RegionNotFoundException(regionName);
    }
}
