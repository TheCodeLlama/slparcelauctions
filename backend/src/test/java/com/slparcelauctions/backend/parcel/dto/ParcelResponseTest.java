package com.slparcelauctions.backend.parcel.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.parcel.Parcel;
import com.slparcelauctions.backend.testsupport.TestRegions;

/**
 * Unit tests for the {@link ParcelResponse#from(Parcel)} mapper.
 *
 * <p>The detail page's {@code VisitInSecondLifeBlock} reads
 * {@code positionX/Y/Z} straight off this DTO; losing those here means
 * every teleport link silently falls back to the region-centre 128/128/0
 * default, so we assert the passthrough explicitly.
 */
class ParcelResponseTest {

    @Test
    void from_populatesPositionsFromParcelEntity() {
        Parcel parcel = Parcel.builder()
                .region(TestRegions.mainland())
                .id(1L)
                .slParcelUuid(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                                                .positionX(45.5)
                .positionY(72.25)
                .positionZ(24.0)
                .areaSqm(1024)
                                .verified(true)
                .createdAt(OffsetDateTime.parse("2026-04-17T00:00:00Z"))
                .build();

        ParcelResponse response = ParcelResponse.from(parcel);

        assertThat(response.positionX()).isEqualTo(45.5);
        assertThat(response.positionY()).isEqualTo(72.25);
        assertThat(response.positionZ()).isEqualTo(24.0);
    }

    @Test
    void from_passesThroughNullPositionsForLegacyRows() {
        // Parcel rows ingested before the positions columns were populated
        // (or any row where the World API returned no position) must round-
        // trip as null so the frontend can surface the region-centre
        // fallback explicitly.
        Parcel parcel = Parcel.builder()
                .region(TestRegions.mainland())
                .id(2L)
                .slParcelUuid(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                                                                .positionX(null)
                .positionY(null)
                .positionZ(null)
                .areaSqm(512)
                                .verified(true)
                .createdAt(OffsetDateTime.parse("2026-04-17T00:00:00Z"))
                .build();

        ParcelResponse response = ParcelResponse.from(parcel);

        assertThat(response.positionX()).isNull();
        assertThat(response.positionY()).isNull();
        assertThat(response.positionZ()).isNull();
    }
}
