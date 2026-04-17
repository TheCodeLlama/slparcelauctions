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
import com.slparcelauctions.backend.sl.MainlandContinents;
import com.slparcelauctions.backend.sl.SlMapApiClient;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.GridCoordinates;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.exception.NotMainlandException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Looks up a parcel by UUID, fetching World API + Map API metadata on first
 * sight and caching into the {@code parcels} table. Shared row — multiple
 * auctions may reference the same parcel. No ownership check here; ownership
 * is per-auction and runs at {@code /verify} time (Task 6).
 *
 * <p>Mainland check runs against {@link MainlandContinents}. Parcels outside
 * every Mainland bounding box are rejected with {@link NotMainlandException}
 * (HTTP 422) — Phase 1 supports Mainland only.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParcelLookupService {

    private final SlWorldApiClient worldApi;
    private final SlMapApiClient mapApi;
    private final ParcelRepository repo;
    private final Clock clock;

    @Transactional
    public ParcelResponse lookup(UUID slParcelUuid) {
        Optional<Parcel> existing = repo.findBySlParcelUuid(slParcelUuid);
        if (existing.isPresent()) {
            return ParcelResponse.from(existing.get());
        }

        ParcelMetadata meta = worldApi.fetchParcel(slParcelUuid).block();
        GridCoordinates coords = mapApi.resolveRegion(meta.regionName()).block();

        String continent = MainlandContinents.continentAt(coords.gridX(), coords.gridY())
                .orElseThrow(() -> new NotMainlandException(coords.gridX(), coords.gridY()));

        OffsetDateTime now = OffsetDateTime.now(clock);
        Parcel parcel = Parcel.builder()
                .slParcelUuid(slParcelUuid)
                .ownerUuid(meta.ownerUuid())
                .ownerType(meta.ownerType())
                .regionName(meta.regionName())
                .gridX(coords.gridX())
                .gridY(coords.gridY())
                .continentName(continent)
                .areaSqm(meta.areaSqm())
                .description(meta.description())
                .snapshotUrl(meta.snapshotUrl())
                .maturityRating(meta.maturityRating())
                .positionX(meta.positionX())
                .positionY(meta.positionY())
                .positionZ(meta.positionZ())
                .slurl(buildSlurl(meta.regionName(), meta.positionX(), meta.positionY(), meta.positionZ()))
                .verified(true)
                .verifiedAt(now)
                .lastChecked(now)
                .build();
        parcel = repo.save(parcel);
        log.info("Parcel row created: id={}, uuid={}, region={}, continent={}",
                parcel.getId(), slParcelUuid, meta.regionName(), continent);
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
