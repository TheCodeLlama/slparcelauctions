package com.slparcelauctions.backend.realty.browse.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.slparcelauctions.backend.realty.browse.GroupsSortKey;
import com.slparcelauctions.backend.realty.rating.dto.GroupRatingDto;

class RealtyGroupCardDtoTest {

    @Test
    void carriesEveryFieldFromTheSpec() {
        var rating = new GroupRatingDto(4.5, 12L);
        var dto = new RealtyGroupCardDto(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "Sunset Realty",
                "sunset-realty",
                "Mainland parcels across Heterocera.",
                "/api/v1/realty-groups/.../logo/image?variant=light",
                "/api/v1/realty-groups/.../logo/image?variant=dark",
                "/api/v1/realty-groups/.../cover/image?variant=light",
                null,
                OffsetDateTime.parse("2026-01-15T00:00:00Z"),
                4,
                8,
                3,
                17,
                rating);

        assertThat(dto.name()).isEqualTo("Sunset Realty");
        assertThat(dto.slug()).isEqualTo("sunset-realty");
        assertThat(dto.tagline()).isEqualTo("Mainland parcels across Heterocera.");
        assertThat(dto.memberCount()).isEqualTo(4);
        assertThat(dto.activeListingsCount()).isEqualTo(3);
        assertThat(dto.completedSalesCount()).isEqualTo(17);
        assertThat(dto.rating().reviewCount()).isEqualTo(12L);
    }

    @Test
    void sortKeyEnumHasFourValues() {
        assertThat(GroupsSortKey.values())
            .containsExactlyInAnyOrder(
                GroupsSortKey.RATING,
                GroupsSortKey.NEWEST,
                GroupsSortKey.MOST_ACTIVE_LISTINGS,
                GroupsSortKey.MOST_SALES);
    }
}
