package com.slparcelauctions.backend.parcel.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;

/**
 * Unit tests for the {@link ParcelResponse#from(AuctionParcelSnapshot)} mapper.
 *
 * <p>The detail page's {@code VisitInSecondLifeBlock} reads
 * {@code positionX/Y/Z} straight off this DTO; losing those here means
 * every teleport link silently falls back to the region-centre 128/128/0
 * default, so we assert the passthrough explicitly.
 */
class ParcelResponseTest {

    @Test
    void from_populatesPositionsFromSnapshot() {
        AuctionParcelSnapshot snapshot = AuctionParcelSnapshot.builder()
                .slParcelUuid(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .ownerType("agent").ownerName("Owner").parcelName("Test Parcel")
                .regionName("Coniston").regionMaturityRating("MODERATE")
                .positionX(45.5).positionY(72.25).positionZ(24.0)
                .areaSqm(1024)
                .build();

        ParcelResponse response = ParcelResponse.from(snapshot);

        assertThat(response.positionX()).isEqualTo(45.5);
        assertThat(response.positionY()).isEqualTo(72.25);
        assertThat(response.positionZ()).isEqualTo(24.0);
    }

    @Test
    void from_passesThroughNullPositionsForUnpopulatedRows() {
        // Snapshot rows where the World API returned no position must round-
        // trip as null so the frontend can surface the region-centre
        // fallback explicitly.
        AuctionParcelSnapshot snapshot = AuctionParcelSnapshot.builder()
                .slParcelUuid(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                .ownerType("agent").ownerName("Owner").parcelName("Test Parcel")
                .regionName("Coniston").regionMaturityRating("MODERATE")
                .positionX(null).positionY(null).positionZ(null)
                .areaSqm(512)
                .build();

        ParcelResponse response = ParcelResponse.from(snapshot);

        assertThat(response.positionX()).isNull();
        assertThat(response.positionY()).isNull();
        assertThat(response.positionZ()).isNull();
    }
}
