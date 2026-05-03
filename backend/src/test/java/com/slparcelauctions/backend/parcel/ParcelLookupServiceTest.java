package com.slparcelauctions.backend.parcel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.slparcelauctions.backend.parcel.ParcelLookupService.ParcelLookupResult;
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

    private static final Instant FIXED = Instant.parse("2026-04-16T12:00:00Z");

    @Mock SlWorldApiClient worldApi;
    @Mock RegionService regionService;

    ParcelLookupService service;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(FIXED, ZoneOffset.UTC);
        service = new ParcelLookupService(worldApi, regionService, fixed);
    }

    @Test
    void lookup_mainlandUuid_returnsPopulatedResponse() {
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
        when(worldApi.fetchParcelPage(parcelUuid))
                .thenReturn(Mono.just(new ParcelPageData(meta, regionUuid)));
        when(worldApi.fetchRegionPage(regionUuid)).thenReturn(Mono.just(regionPage));
        when(regionService.upsert(regionPage)).thenReturn(region);

        ParcelLookupResult result = service.lookup(parcelUuid);
        ParcelResponse response = result.response();

        assertThat(result.region()).isEqualTo(region);
        assertThat(response.slParcelUuid()).isEqualTo(parcelUuid);
        assertThat(response.regionName()).isEqualTo("Coniston");
        assertThat(response.gridX()).isEqualTo(1014.0);
        assertThat(response.gridY()).isEqualTo(1014.0);
        assertThat(response.regionMaturityRating()).isEqualTo("MODERATE");
        assertThat(response.ownerName()).isEqualTo("Sunset Bay Holdings");
        assertThat(response.slurl()).contains("Coniston").contains("128").contains("64");
        assertThat(response.verified()).isTrue();
        assertThat(response.positionX()).isEqualTo(128.0);
        assertThat(response.positionY()).isEqualTo(64.0);
        assertThat(response.positionZ()).isEqualTo(22.0);
        assertThat(response.areaSqm()).isEqualTo(1024);
        assertThat(response.verifiedAt())
                .isEqualTo(OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC));
    }

    @Test
    void lookup_alwaysFetchesFreshFromSL() {
        // ParcelLookupService is stateless — no caching, every call hits the SL API.
        UUID parcelUuid = UUID.fromString("99999999-9999-9999-9999-999999999999");
        UUID ownerUuid = UUID.fromString("88888888-8888-8888-8888-888888888888");
        UUID regionUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        ParcelMetadata meta = new ParcelMetadata(
                parcelUuid, ownerUuid, "agent", "New Owner Holdings",
                "Fresh Parcel Name", "Coniston",
                2048, "fresh desc", "http://example.com/snap2.jpg", null,
                64.0, 32.0, 11.0);
        RegionPageData regionPage = new RegionPageData(
                regionUuid, "Coniston", 1014.0, 1014.0, "M_NOT");
        Region region = Region.builder()
                .id(7L).slUuid(regionUuid).name("Coniston")
                .gridX(1014.0).gridY(1014.0).maturityRating("MODERATE").build();
        when(worldApi.fetchParcelPage(parcelUuid))
                .thenReturn(Mono.just(new ParcelPageData(meta, regionUuid)));
        when(worldApi.fetchRegionPage(regionUuid)).thenReturn(Mono.just(regionPage));
        when(regionService.upsert(regionPage)).thenReturn(region);

        ParcelLookupResult result = service.lookup(parcelUuid);
        ParcelResponse response = result.response();

        assertThat(response.parcelName()).isEqualTo("Fresh Parcel Name");
        assertThat(response.ownerName()).isEqualTo("New Owner Holdings");
        assertThat(response.ownerUuid()).isEqualTo(ownerUuid);
        assertThat(response.regionName()).isEqualTo("Coniston");
        assertThat(response.areaSqm()).isEqualTo(2048);
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
