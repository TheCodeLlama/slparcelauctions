package com.slparcelauctions.backend.parcel;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.slparcelauctions.backend.parcel.dto.ParcelResponse;
import com.slparcelauctions.backend.region.Region;
import com.slparcelauctions.backend.region.RegionService;
import com.slparcelauctions.backend.region.dto.RegionPageData;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.dto.ParcelPageData;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Stateless parcel lookup — fetches the parcel page + region page from the SL
 * World API, upserts the {@link Region} row (for global identity), and returns
 * a {@link ParcelLookupResult} carrying a {@link ParcelResponse} and the
 * resolved region. Nothing else is persisted; callers are responsible for
 * writing any snapshot they need.
 *
 * <p>Two outbound HTTP calls per invocation: parcel page + region page. The
 * Mainland check happens inside {@link RegionService#upsert} — non-Mainland
 * regions are rejected with {@code NotMainlandException} (HTTP 422) before
 * this method returns.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParcelLookupService {

    private final SlWorldApiClient worldApi;
    private final RegionService regionService;
    private final Clock clock;

    /**
     * Bundles the flat {@link ParcelResponse} with the hydrated {@link Region}
     * so callers can build an {@link com.slparcelauctions.backend.auction.AuctionParcelSnapshot}
     * without a second query.
     */
    public record ParcelLookupResult(ParcelResponse response, Region region) {}

    public ParcelLookupResult lookup(UUID slParcelUuid) {
        ParcelPageData parcelPage = worldApi.fetchParcelPage(slParcelUuid).block();
        RegionPageData regionPage = worldApi.fetchRegionPage(parcelPage.regionUuid()).block();
        Region region = regionService.upsert(regionPage);

        ParcelMetadata meta = parcelPage.parcel();
        OffsetDateTime now = OffsetDateTime.now(clock);
        String slurl = buildSlurl(region.getName(), meta.positionX(), meta.positionY(), meta.positionZ());

        ParcelResponse response = new ParcelResponse(
                slParcelUuid,
                meta.ownerUuid(),
                meta.ownerType(),
                meta.ownerName(),
                meta.parcelName(),
                region.getId(),
                region.getName(),
                region.getMaturityRating(),
                region.getGridX(),
                region.getGridY(),
                meta.positionX(),
                meta.positionY(),
                meta.positionZ(),
                meta.areaSqm(),
                meta.description(),
                meta.snapshotUrl(),
                slurl,
                true,
                now,
                now);

        log.info("Parcel lookup: uuid={} region_id={} region={}", slParcelUuid, region.getId(), region.getName());
        return new ParcelLookupResult(response, region);
    }

    private String buildSlurl(String regionName, Double x, Double y, Double z) {
        String encoded = URLEncoder.encode(regionName == null ? "" : regionName, StandardCharsets.UTF_8);
        return "https://maps.secondlife.com/secondlife/" + encoded
                + "/" + (x == null ? 128 : x.intValue())
                + "/" + (y == null ? 128 : y.intValue())
                + "/" + (z == null ? 22 : z.intValue());
    }
}
