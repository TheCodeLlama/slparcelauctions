package com.slparcelauctions.backend.sl;

import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

import com.slparcelauctions.backend.region.dto.RegionPageData;
import com.slparcelauctions.backend.sl.dto.GroupPageData;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.dto.ParcelPageData;
import com.slparcelauctions.backend.sl.exception.ExternalApiTimeoutException;
import com.slparcelauctions.backend.sl.exception.ParcelNotFoundInSlException;
import com.slparcelauctions.backend.sl.exception.RegionNotFoundException;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Fetches and parses the SL World API pages used by the parcel-lookup and
 * realty-group SL-group verification flows:
 * <ul>
 *   <li>{@code world.secondlife.com/place/{parcelUuid}} — parcel metadata
 *       (owner, area, description, position) plus the region's SL UUID via
 *       a body link.</li>
 *   <li>{@code world.secondlife.com/region/{regionUuid}} — region metadata
 *       (name, grid coordinates in region units, maturity rating).</li>
 *   <li>{@code world.secondlife.com/group/{slGroupUuid}} — group metadata
 *       (display name, founder UUID, About/Charter text) used by the
 *       realty-group SL-group registration + verification flow.</li>
 * </ul>
 *
 * <p>All endpoints retry 5xx / network errors with exponential backoff,
 * fail fast on 404 (parcel doesn't exist / region doesn't exist), and
 * surface other failures as {@link ExternalApiTimeoutException}. Parcel /
 * region parsing failures throw {@link ParcelIngestException} (mapped to
 * 422). Group parsing tolerates missing fields — every field other than
 * the input {@code slGroupUuid} may be {@code null}; the caller decides
 * which absences are fatal.
 */
@Component
@Slf4j
public class SlWorldApiClient {

    private static final Pattern REGION_UUID_FROM_HREF = Pattern.compile(
            "/region/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    private static final Pattern RESIDENT_UUID_FROM_HREF = Pattern.compile(
            "/resident/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

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

    public Mono<ParcelPageData> fetchParcelPage(UUID parcelUuid) {
        return webClient.get()
                .uri("/place/{uuid}", parcelUuid)
                .retrieve()
                .onStatus(HttpStatus.NOT_FOUND::equals,
                        r -> Mono.error(new ParcelNotFoundInSlException(parcelUuid)))
                .bodyToMono(String.class)
                .retryWhen(Retry.backoff(retryAttempts, Duration.ofMillis(retryBackoffMs))
                        .filter(t -> isTransient(t, ParcelNotFoundInSlException.class)))
                .onErrorMap(
                        throwable -> !(throwable instanceof ParcelNotFoundInSlException),
                        throwable -> new ExternalApiTimeoutException("World", throwable.getMessage()))
                .map(html -> parseParcelHtml(parcelUuid, html));
    }

    public Mono<RegionPageData> fetchRegionPage(UUID regionUuid) {
        return webClient.get()
                .uri("/region/{uuid}", regionUuid)
                .retrieve()
                .onStatus(HttpStatus.NOT_FOUND::equals,
                        r -> Mono.error(new RegionNotFoundException(regionUuid.toString())))
                .bodyToMono(String.class)
                .retryWhen(Retry.backoff(retryAttempts, Duration.ofMillis(retryBackoffMs))
                        .filter(t -> isTransient(t, RegionNotFoundException.class)))
                .onErrorMap(
                        throwable -> !(throwable instanceof RegionNotFoundException),
                        throwable -> new ExternalApiTimeoutException("World", throwable.getMessage()))
                .map(this::parseRegionHtml);
    }

    /**
     * Fetches {@code world.secondlife.com/group/{slGroupUuid}} and parses the fields
     * the realty-group SL-group verification flow needs: display name, founder UUID,
     * and About / Charter text. Any field the parser cannot extract is set to
     * {@code null}; the caller decides whether that is fatal (e.g., About-text poll
     * tolerates {@code aboutText == null} as a no-match, founder-terminal callback
     * treats {@code founderUuid == null} as 422).
     *
     * <p><b>Parser fidelity:</b> the selectors mirror the parcel-page idiom (Jsoup
     * meta + body element lookups) but have not been validated against a live
     * {@code world.secondlife.com/group/{uuid}} response at the time of writing.
     * If the parser returns {@code null} for fields that should be present, run
     * {@code curl world.secondlife.com/group/{uuid}} against a real group and
     * adjust {@link #parseGroupHtml(UUID, String)} accordingly.
     */
    public Mono<GroupPageData> fetchGroupPage(UUID slGroupUuid) {
        return webClient.get()
                .uri("/group/{uuid}", slGroupUuid)
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(Retry.backoff(retryAttempts, Duration.ofMillis(retryBackoffMs))
                        .filter(this::isTransientGroupError))
                // Pass {@link WebClientResponseException.NotFound} through unwrapped so
                // {@code SlGroupReverifyService} (sub-project F §13.2) can distinguish
                // "SL group has been deleted" (404 → GROUP_NOT_FOUND drift) from
                // "World API timed out" (other failures → fetch-failure counter /
                // FETCH_FAILED_REPEATEDLY after threshold).
                .onErrorMap(
                        throwable -> !(throwable instanceof ExternalApiTimeoutException)
                                && !(throwable instanceof WebClientResponseException.NotFound),
                        throwable -> new ExternalApiTimeoutException("World", throwable.getMessage()))
                .map(html -> parseGroupHtml(slGroupUuid, html));
    }

    private boolean isTransientGroupError(Throwable t) {
        if (t instanceof WebClientResponseException e) {
            return e.getStatusCode().is5xxServerError();
        }
        return true;
    }

    private boolean isTransient(Throwable t, Class<? extends Throwable> nonTransient) {
        if (nonTransient.isInstance(t)) {
            return false;
        }
        if (t instanceof WebClientResponseException e) {
            return e.getStatusCode().is5xxServerError();
        }
        // Network-level exceptions (connect timeout, read timeout) are also transient
        return true;
    }

    private ParcelPageData parseParcelHtml(UUID parcelUuid, String html) {
        Document doc = Jsoup.parse(html);
        String[] xyz = parseLocation(meta(doc, "name", "location"));
        ParcelMetadata parcel = new ParcelMetadata(
                parcelUuid,
                optionalUuid(meta(doc, "name", "ownerid")),
                meta(doc, "name", "ownertype"),
                meta(doc, "name", "owner"),
                meta(doc, "name", "parcel"),
                meta(doc, "name", "region"),
                optionalInt(meta(doc, "name", "area")),
                description(doc),
                snapshotUrl(doc),
                null,                                    // maturity is region-scoped — see ParcelMetadata doc
                optionalDouble(xyz[0]),
                optionalDouble(xyz[1]),
                optionalDouble(xyz[2]));
        UUID regionUuid = parseRegionUuidFromBody(doc);
        return new ParcelPageData(parcel, regionUuid);
    }

    private RegionPageData parseRegionHtml(String html) {
        Document doc = Jsoup.parse(html);
        UUID slUuid = optionalUuid(meta(doc, "name", "regionid"));
        if (slUuid == null) {
            throw new ParcelIngestException("regionid missing from region page");
        }
        String name = meta(doc, "name", "region");
        if (name == null || name.isBlank()) {
            throw new ParcelIngestException("region name missing from region page");
        }
        Double gridX = optionalDouble(meta(doc, "name", "gridx"));
        Double gridY = optionalDouble(meta(doc, "name", "gridy"));
        if (gridX == null || gridY == null) {
            throw new ParcelIngestException("gridx/gridy missing from region page");
        }
        String maturityRaw = meta(doc, "name", "mat");
        return new RegionPageData(slUuid, name, gridX, gridY, maturityRaw);
    }

    /**
     * Parse {@code world.secondlife.com/group/{uuid}} HTML into a {@link GroupPageData}.
     * Defensive: every field other than the input UUID may be {@code null} if the
     * selector misses. Selectors are validated against a live capture of
     * {@code world.secondlife.com/group/79f06955-38f4-3124-25b3-f5506c85828f} (the
     * SLParcels group); the corresponding test fixture lives at
     * {@code backend/src/test/resources/sl/group-page-slparcels.html}.
     *
     * <ul>
     *   <li><b>name</b> — {@code .details h1}; fallback to the {@code <title>}
     *       element text content.</li>
     *   <li><b>aboutText</b> — {@code .details p.desc}; {@code null} when blank
     *       (the live SL page emits an empty {@code <p class="desc">} for groups
     *       with no charter).</li>
     *   <li><b>founderUuid</b> — {@code <meta name="founderid">}; fallback to
     *       any anchor matching {@code href^="/resident/<uuid>"}.</li>
     * </ul>
     *
     * <p>Package-private static so the parser test can exercise it without going
     * through a live {@link WebClient}.
     */
    static GroupPageData parseGroupHtml(UUID slGroupUuid, String html) {
        Document doc = Jsoup.parse(html);
        return new GroupPageData(
                slGroupUuid,
                parseGroupName(doc),
                parseGroupAboutText(doc),
                parseGroupFounderUuid(doc));
    }

    private static String parseGroupName(Document doc) {
        Element el = doc.selectFirst(".details h1");
        if (el != null) {
            String text = el.text();
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        Element titleEl = doc.selectFirst("title");
        if (titleEl != null) {
            String text = titleEl.text();
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private static String parseGroupAboutText(Document doc) {
        Element el = doc.selectFirst(".details p.desc");
        if (el == null) {
            return null;
        }
        String text = el.text();
        return (text != null && !text.isBlank()) ? text : null;
    }

    private static UUID parseGroupFounderUuid(Document doc) {
        Element metaEl = doc.selectFirst("meta[name=founderid]");
        String fromMeta = metaEl != null ? metaEl.attr("content") : null;
        UUID metaUuid = optionalUuidStatic(fromMeta);
        if (metaUuid != null) {
            return metaUuid;
        }
        Element link = doc.selectFirst("a[href^=/resident/]");
        if (link == null) {
            return null;
        }
        Matcher m = RESIDENT_UUID_FROM_HREF.matcher(link.attr("href"));
        if (!m.find()) {
            return null;
        }
        return optionalUuidStatic(m.group(1));
    }

    private static UUID optionalUuidStatic(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ignored) {
            log.warn("World API returned unparseable UUID: {}", s);
            return null;
        }
    }

    private UUID parseRegionUuidFromBody(Document doc) {
        Element link = doc.selectFirst("a[href^=/region/]");
        if (link == null) {
            throw new ParcelIngestException("region link missing from parcel page");
        }
        String href = link.attr("href");
        Matcher m = REGION_UUID_FROM_HREF.matcher(href);
        if (!m.find()) {
            throw new ParcelIngestException(
                    "region link on parcel page is not a UUID: " + href);
        }
        try {
            return UUID.fromString(m.group(1));
        } catch (IllegalArgumentException e) {
            throw new ParcelIngestException(
                    "region link UUID failed to parse: " + m.group(1), e);
        }
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
