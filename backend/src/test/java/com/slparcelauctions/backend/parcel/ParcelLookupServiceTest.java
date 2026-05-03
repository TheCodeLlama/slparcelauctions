package com.slparcelauctions.backend.parcel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.parcel.dto.ParcelResponse;
import com.slparcelauctions.backend.region.Region;
import com.slparcelauctions.backend.region.RegionService;
import com.slparcelauctions.backend.region.dto.RegionPageData;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.dto.ParcelPageData;
import com.slparcelauctions.backend.sl.exception.NotMainlandException;
import com.slparcelauctions.backend.sl.exception.ParcelNotFoundInSlException;

import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class ParcelLookupServiceTest {

    @Mock SlWorldApiClient worldApi;
    @Mock RegionService regionService;
    @Mock ParcelRepository repo;

    ParcelLookupService service;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(Instant.parse("2026-04-16T12:00:00Z"), ZoneOffset.UTC);
        service = new ParcelLookupService(worldApi, regionService, repo, fixed);
    }

    @Test
    void lookup_newUuidOnMainland_createsParcelAndReturnsResponse() {
        UUID parcelUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID ownerUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID regionUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        ParcelMetadata meta = new ParcelMetadata(
                parcelUuid, ownerUuid, "agent", "Sunset Bay Holdings",
                "Sunset Bay", "Coniston",
                1024, "Waterfront", "http://example.com/snap.jpg", null,
                128.0, 64.0, 22.0);
        RegionPageData regionPage = new RegionPageData(
                regionUuid, "Coniston", 1014.0, 1014.0, "M_NOT");
        Region region = Region.builder()
                .id(7L).slUuid(regionUuid).name("Coniston")
                .gridX(1014.0).gridY(1014.0).maturityRating("MODERATE").build();
        when(repo.findBySlParcelUuid(parcelUuid)).thenReturn(Optional.empty());
        when(worldApi.fetchParcelPage(parcelUuid))
                .thenReturn(Mono.just(new ParcelPageData(meta, regionUuid)));
        when(worldApi.fetchRegionPage(regionUuid)).thenReturn(Mono.just(regionPage));
        when(regionService.upsert(regionPage)).thenReturn(region);
        when(repo.save(any(Parcel.class))).thenAnswer(inv -> {
            Parcel p = inv.getArgument(0);
            p.setId(42L);
            return p;
        });

        ParcelResponse result = service.lookup(parcelUuid);

        assertThat(result.id()).isEqualTo(42L);
        assertThat(result.regionName()).isEqualTo("Coniston");
        assertThat(result.gridX()).isEqualTo(1014.0);
        assertThat(result.gridY()).isEqualTo(1014.0);
        assertThat(result.maturityRating()).isEqualTo("MODERATE");
        assertThat(result.ownerName()).isEqualTo("Sunset Bay Holdings");
        assertThat(result.slurl()).contains("Coniston").contains("128").contains("64");
        assertThat(result.verified()).isTrue();
        assertThat(result.positionX()).isEqualTo(128.0);
        assertThat(result.positionY()).isEqualTo(64.0);
        assertThat(result.positionZ()).isEqualTo(22.0);
    }

    @Test
    void lookup_existingUuid_refreshesFieldsFromSL() {
        // Lookup is user-initiated and must reflect current SL state. The
        // existing parcels row is reused as a stable FK target for auctions
        // but its SL-sourced fields are refreshed in place every call.
        UUID parcelUuid = UUID.fromString("99999999-9999-9999-9999-999999999999");
        UUID newOwner = UUID.fromString("88888888-8888-8888-8888-888888888888");
        UUID regionUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        Region staleRegion = Region.builder().id(6L).name("OldRegion")
                .gridX(1000.0).gridY(1000.0).maturityRating("GENERAL").build();
        Parcel existing = Parcel.builder()
                .id(99L).slParcelUuid(parcelUuid).region(staleRegion)
                .ownerUuid(UUID.randomUUID()).parcelName("Stale name")
                .verified(true).verifiedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"))
                .build();
        ParcelMetadata fresh = new ParcelMetadata(
                parcelUuid, newOwner, "agent", "New Owner Holdings",
                "Fresh Parcel Name", "Coniston",
                2048, "fresh desc", "http://example.com/snap2.jpg", null,
                64.0, 32.0, 11.0);
        RegionPageData regionPage = new RegionPageData(
                regionUuid, "Coniston", 1014.0, 1014.0, "M_NOT");
        Region freshRegion = Region.builder()
                .id(7L).slUuid(regionUuid).name("Coniston")
                .gridX(1014.0).gridY(1014.0).maturityRating("MODERATE").build();
        when(repo.findBySlParcelUuid(parcelUuid)).thenReturn(Optional.of(existing));
        when(worldApi.fetchParcelPage(parcelUuid))
                .thenReturn(Mono.just(new ParcelPageData(fresh, regionUuid)));
        when(worldApi.fetchRegionPage(regionUuid)).thenReturn(Mono.just(regionPage));
        when(regionService.upsert(regionPage)).thenReturn(freshRegion);
        when(repo.save(any(Parcel.class))).thenAnswer(inv -> inv.getArgument(0));

        ParcelResponse result = service.lookup(parcelUuid);

        // Same row id (FK target stays stable for any auctions referencing it).
        assertThat(result.id()).isEqualTo(99L);
        // Refreshed fields from SL.
        assertThat(result.parcelName()).isEqualTo("Fresh Parcel Name");
        assertThat(result.ownerName()).isEqualTo("New Owner Holdings");
        assertThat(result.ownerUuid()).isEqualTo(newOwner);
        assertThat(result.regionName()).isEqualTo("Coniston");
        assertThat(result.areaSqm()).isEqualTo(2048);
        // verifiedAt is the first-time-verified stamp; not bumped on refresh.
        assertThat(result.verifiedAt())
                .isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        verify(worldApi).fetchParcelPage(parcelUuid);
        verify(worldApi).fetchRegionPage(regionUuid);
        verify(regionService).upsert(regionPage);
    }

    @Test
    void lookup_worldApi404_propagates() {
        UUID parcelUuid = UUID.randomUUID();
        when(worldApi.fetchParcelPage(parcelUuid))
                .thenReturn(Mono.error(new ParcelNotFoundInSlException(parcelUuid)));

        assertThatThrownBy(() -> service.lookup(parcelUuid))
                .isInstanceOf(ParcelNotFoundInSlException.class);
    }

    @Test
    void lookup_nonMainlandCoords_throwsNotMainland() {
        UUID parcelUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        UUID regionUuid = UUID.randomUUID();
        ParcelMetadata meta = new ParcelMetadata(
                parcelUuid, ownerUuid, "agent", "X Holdings", "X", "SomeEstate", 1024,
                null, null, null, 128.0, 128.0, 22.0);
        RegionPageData regionPage = new RegionPageData(
                regionUuid, "SomeEstate", 390.0, 390.0, "PG_NOT");
        when(worldApi.fetchParcelPage(parcelUuid))
                .thenReturn(Mono.just(new ParcelPageData(meta, regionUuid)));
        when(worldApi.fetchRegionPage(regionUuid)).thenReturn(Mono.just(regionPage));
        when(regionService.upsert(regionPage))
                .thenThrow(new NotMainlandException(390.0, 390.0));

        assertThatThrownBy(() -> service.lookup(parcelUuid))
                .isInstanceOf(NotMainlandException.class);
    }
}
