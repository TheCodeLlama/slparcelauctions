package com.slparcelauctions.backend.parcel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
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
    void lookup_existingUuid_shortCircuitsNoExternalCalls() {
        UUID parcelUuid = UUID.randomUUID();
        Region region = Region.builder().id(7L).name("Sansara")
                .gridX(1000.0).gridY(1000.0).maturityRating("GENERAL").build();
        Parcel existing = Parcel.builder()
                .id(99L).slParcelUuid(parcelUuid).region(region).build();
        when(repo.findBySlParcelUuid(parcelUuid)).thenReturn(Optional.of(existing));

        ParcelResponse result = service.lookup(parcelUuid);

        assertThat(result.id()).isEqualTo(99L);
        verify(worldApi, never()).fetchParcelPage(any());
        verify(worldApi, never()).fetchRegionPage(any());
        verify(regionService, never()).upsert(any());
    }

    @Test
    void lookup_worldApi404_propagates() {
        UUID parcelUuid = UUID.randomUUID();
        when(repo.findBySlParcelUuid(parcelUuid)).thenReturn(Optional.empty());
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
        when(repo.findBySlParcelUuid(parcelUuid)).thenReturn(Optional.empty());
        when(worldApi.fetchParcelPage(parcelUuid))
                .thenReturn(Mono.just(new ParcelPageData(meta, regionUuid)));
        when(worldApi.fetchRegionPage(regionUuid)).thenReturn(Mono.just(regionPage));
        when(regionService.upsert(regionPage))
                .thenThrow(new NotMainlandException(390.0, 390.0));

        assertThatThrownBy(() -> service.lookup(parcelUuid))
                .isInstanceOf(NotMainlandException.class);
    }
}
