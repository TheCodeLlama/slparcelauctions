package com.slparcelauctions.backend.sl;

import java.time.Duration;
import java.util.UUID;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.exception.ExternalApiTimeoutException;
import com.slparcelauctions.backend.sl.exception.ParcelNotFoundInSlException;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Fetches parcel metadata HTML from {@code world.secondlife.com/place/{uuid}}
 * and parses meta tags with Jsoup. Unofficial API - retry 5xx with backoff,
 * fail fast on 404 (parcel does not exist), fail-hard on exhaustion.
 */
@Component
@Slf4j
public class SlWorldApiClient {

    private final WebClient webClient;
    private final int retryAttempts;
    private final long retryBackoffMs;

    @Autowired
    public SlWorldApiClient(
            @Qualifier("slWorldApiWebClient") WebClient webClient,
            @Value("${slpa.world-api.retry-attempts:3}") int retryAttempts,
            @Value("${slpa.world-api.retry-backoff-ms:500}") long retryBackoffMs) {
        this.webClient = webClient;
        this.retryAttempts = retryAttempts;
        this.retryBackoffMs = retryBackoffMs;
    }

    public Mono<ParcelMetadata> fetchParcel(UUID parcelUuid) {
        return webClient.get()
                .uri("/place/{uuid}", parcelUuid)
                .retrieve()
                .onStatus(HttpStatus.NOT_FOUND::equals,
                        r -> Mono.error(new ParcelNotFoundInSlException(parcelUuid)))
                .bodyToMono(String.class)
                .retryWhen(Retry.backoff(retryAttempts, Duration.ofMillis(retryBackoffMs))
                        .filter(this::isTransient))
                .onErrorMap(
                        throwable -> !(throwable instanceof ParcelNotFoundInSlException),
                        throwable -> new ExternalApiTimeoutException("World", throwable.getMessage()))
                .map(html -> parseHtml(parcelUuid, html));
    }

    private boolean isTransient(Throwable t) {
        if (t instanceof ParcelNotFoundInSlException) {
            // 404 is mapped before bodyToMono via onStatus; never retry.
            return false;
        }
        if (t instanceof WebClientResponseException e) {
            return e.getStatusCode().is5xxServerError();
        }
        // Network-level exceptions (connect timeout, read timeout) are also transient
        return true;
    }

    private ParcelMetadata parseHtml(UUID parcelUuid, String html) {
        Document doc = Jsoup.parse(html);
        String[] xyz = parseLocation(meta(doc, "name", "location"));
        // maturityRating stays null at ingest — the parcel page does not reliably
        // expose it; SL scopes maturity to the region, not the parcel. Filled in
        // at listing-creation time via user input until a regions table lands.
        return new ParcelMetadata(
                parcelUuid,
                optionalUuid(meta(doc, "name", "ownerid")),
                meta(doc, "name", "ownertype"),
                meta(doc, "name", "parcel"),
                meta(doc, "name", "region"),
                optionalInt(meta(doc, "name", "area")),
                description(doc),
                snapshotUrl(doc),
                null,
                optionalDouble(xyz[0]),
                optionalDouble(xyz[1]),
                optionalDouble(xyz[2]));
    }

    private String meta(Document doc, String attr, String value) {
        Element e = doc.selectFirst("meta[" + attr + "=" + value + "]");
        return e != null ? e.attr("content") : null;
    }

    private String description(Document doc) {
        Element e = doc.selectFirst("p.desc");
        return e != null ? e.text() : null;
    }

    // Snapshot URL: prefer the meta tag if SL populated it, fall back to the
    // <img class="parcelimg"> src that the page always renders for parcels with
    // a snapshot uploaded. The meta tag is empty for many real parcels.
    private String snapshotUrl(Document doc) {
        String fromMeta = meta(doc, "name", "snapshot");
        if (fromMeta != null && !fromMeta.isBlank()) {
            return fromMeta;
        }
        Element img = doc.selectFirst("img.parcelimg");
        return img != null ? img.attr("src") : null;
    }

    // <meta name="location" content="x/y/z"> — slash-separated parcel-local
    // coordinates within the region. Returns three nulls on missing/malformed
    // input so callers stay tolerant.
    private String[] parseLocation(String content) {
        if (content == null || content.isBlank()) {
            return new String[]{null, null, null};
        }
        String[] parts = content.split("/");
        if (parts.length != 3) {
            return new String[]{null, null, null};
        }
        return parts;
    }

    private UUID optionalUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ignored) {
            log.warn("World API returned unparseable UUID: {}", s);
            return null;
        }
    }

    private Integer optionalInt(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double optionalDouble(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
