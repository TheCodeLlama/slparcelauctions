package com.slparcelauctions.backend.parcel;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * Looks up a parcel by UUID, fetching the parcel page + region page on first
 * sight and caching into the {@code parcels} + {@code regions} tables. Two
 * outbound HTTP calls per first-sight lookup (parcel page + region page); the
 * region page fetch always happens — coords on Mainland can in principle
 * change, so we re-resolve on every parcel lookup and {@code RegionService}
 * UPDATEs the row in place when SL has shifted any field. Subsequent
 * lookups of the same parcel UUID short-circuit on the {@code parcels} cache.
 *
 * <p>Mainland check happens inside {@link RegionService#upsert} — non-Mainland
 * regions never get persisted, and the {@code NotMainlandException} (HTTP 422)
 * surfaces from there.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParcelLookupService {

    private final SlWorldApiClient worldApi;
    private final RegionService regionService;
    private final ParcelRepository repo;
    private final Clock clock;

    @Transactional
    public ParcelResponse lookup(UUID slParcelUuid) {
        Optional<Parcel> existing = repo.findBySlParcelUuid(slParcelUuid);
        if (existing.isPresent()) {
            return ParcelResponse.from(existing.get());
        }

        ParcelPageData parcelPage = worldApi.fetchParcelPage(slParcelUuid).block();
        RegionPageData regionPage = worldApi.fetchRegionPage(parcelPage.regionUuid()).block();
        Region region = regionService.upsert(regionPage);

        ParcelMetadata meta = parcelPage.parcel();
        OffsetDateTime now = OffsetDateTime.now(clock);
        Parcel parcel = Parcel.builder()
                .slParcelUuid(slParcelUuid)
                .region(region)
                .ownerUuid(meta.ownerUuid())
                .ownerType(meta.ownerType())
                .ownerName(meta.ownerName())
                .areaSqm(meta.areaSqm())
                .description(meta.description())
                .snapshotUrl(meta.snapshotUrl())
                .positionX(meta.positionX())
                .positionY(meta.positionY())
                .positionZ(meta.positionZ())
                .slurl(buildSlurl(region.getName(), meta.positionX(), meta.positionY(), meta.positionZ()))
                .verified(true)
                .verifiedAt(now)
                .lastChecked(now)
                .build();
        parcel = repo.save(parcel);
        log.info("Parcel row created: id={}, uuid={}, region_id={}, region={}",
                parcel.getId(), slParcelUuid, region.getId(), region.getName());
        return ParcelResponse.from(parcel);
    }

    private String buildSlurl(String regionName, Double x, Double y, Double z) {
        String encoded = URLEncoder.encode(regionName == null ? "" : regionName, StandardCharsets.UTF_8);
        return "https://maps.secondlife.com/secondlife/" + encoded
                + "/" + (x == null ? 128 : x.intValue())
                + "/" + (y == null ? 128 : y.intValue())
                + "/" + (z == null ? 22 : z.intValue());
    }
}
