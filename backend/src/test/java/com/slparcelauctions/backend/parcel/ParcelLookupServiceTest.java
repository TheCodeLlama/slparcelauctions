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
import com.slparcelauctions.backend.sl.SlMapApiClient;
import com.slparcelauctions.backend.sl.SlWorldApiClient;
import com.slparcelauctions.backend.sl.dto.GridCoordinates;
import com.slparcelauctions.backend.sl.dto.ParcelMetadata;
import com.slparcelauctions.backend.sl.exception.NotMainlandException;
import com.slparcelauctions.backend.sl.exception.ParcelNotFoundInSlException;

import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class ParcelLookupServiceTest {

    @Mock SlWorldApiClient worldApi;
    @Mock SlMapApiClient mapApi;
    @Mock ParcelRepository repo;

    ParcelLookupService service;

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(Instant.parse("2026-04-16T12:00:00Z"), ZoneOffset.UTC);
        service = new ParcelLookupService(worldApi, mapApi, repo, fixed);
    }

    @Test
    void lookup_newUuidOnMainland_createsParcelAndReturnsResponse() {
        UUID parcelUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID ownerUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(repo.findBySlParcelUuid(parcelUuid)).thenReturn(Optional.empty());
        when(worldApi.fetchParcel(parcelUuid)).thenReturn(Mono.just(new ParcelMetadata(
                parcelUuid, ownerUuid, "agent", "Sunset Bay", "Coniston",
                1024, "Waterfront", "http://example.com/snap.jpg", "MODERATE",
                128.0, 64.0, 22.0)));
        // Grid coords inside the Sansara continent box (254208..265984, 250368..259328)
        when(mapApi.resolveRegion("Coniston")).thenReturn(Mono.just(new GridCoordinates(260000.0, 254000.0)));
        when(repo.save(any(Parcel.class))).thenAnswer(inv -> {
            Parcel p = inv.getArgument(0);
            p.setId(42L);
            return p;
        });

        ParcelResponse result = service.lookup(parcelUuid);

        assertThat(result.id()).isEqualTo(42L);
        assertThat(result.regionName()).isEqualTo("Coniston");
        assertThat(result.continentName()).isEqualTo("Sansara");
        assertThat(result.slurl()).contains("Coniston").contains("128").contains("64");
        assertThat(result.verified()).isTrue();
    }

    @Test
    void lookup_existingUuid_shortCircuitsNoExternalCalls() {
        UUID parcelUuid = UUID.randomUUID();
        Parcel existing = Parcel.builder()
                .id(99L).slParcelUuid(parcelUuid).regionName("Sansara").build();
        when(repo.findBySlParcelUuid(parcelUuid)).thenReturn(Optional.of(existing));

        ParcelResponse result = service.lookup(parcelUuid);

        assertThat(result.id()).isEqualTo(99L);
        verify(worldApi, never()).fetchParcel(any());
        verify(mapApi, never()).resolveRegion(any());
    }

    @Test
    void lookup_worldApi404_propagates() {
        UUID parcelUuid = UUID.randomUUID();
        when(repo.findBySlParcelUuid(parcelUuid)).thenReturn(Optional.empty());
        when(worldApi.fetchParcel(parcelUuid))
                .thenReturn(Mono.error(new ParcelNotFoundInSlException(parcelUuid)));

        assertThatThrownBy(() -> service.lookup(parcelUuid))
                .isInstanceOf(ParcelNotFoundInSlException.class);
    }

    @Test
    void lookup_nonMainlandCoords_throwsNotMainland() {
        UUID parcelUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        when(repo.findBySlParcelUuid(parcelUuid)).thenReturn(Optional.empty());
        when(worldApi.fetchParcel(parcelUuid)).thenReturn(Mono.just(new ParcelMetadata(
                parcelUuid, ownerUuid, "agent", "X", "SomeEstate", 1024, null, null, "PG",
                128.0, 128.0, 22.0)));
        // Coords outside every Mainland bounding box
        when(mapApi.resolveRegion("SomeEstate")).thenReturn(Mono.just(new GridCoordinates(100000.0, 100000.0)));

        assertThatThrownBy(() -> service.lookup(parcelUuid))
                .isInstanceOf(NotMainlandException.class);
    }
}
