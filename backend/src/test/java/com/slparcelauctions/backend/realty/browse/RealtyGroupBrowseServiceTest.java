package com.slparcelauctions.backend.realty.browse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.slparcelauctions.backend.realty.RealtyGroupRepository;

class RealtyGroupBrowseServiceTest {

    private final RealtyGroupRepository repo = mock(RealtyGroupRepository.class);
    private final RealtyGroupBrowseService service = new RealtyGroupBrowseService(repo);

    @Test
    void taglineTruncatesAt120CharsWithEllipsis() {
        String longDescription = "a".repeat(200);
        RealtyGroupCardProjection p = projection("g", longDescription);
        when(repo.browseCards(eq(null), any()))
            .thenReturn(new PageImpl<>(List.of(p)));

        var result = service.browse(null, GroupsSortKey.RATING, PageRequest.of(0, 20));

        assertThat(result.getContent().get(0).tagline())
            .hasSize(123)
            .endsWith("...");
    }

    @Test
    void taglineLeavesShortDescriptionsUnchanged() {
        RealtyGroupCardProjection p = projection("g", "short");
        when(repo.browseCards(eq(null), any()))
            .thenReturn(new PageImpl<>(List.of(p)));

        var result = service.browse(null, GroupsSortKey.RATING, PageRequest.of(0, 20));

        assertThat(result.getContent().get(0).tagline()).isEqualTo("short");
    }

    @Test
    void mapsSortKeyToRepoSortOrder() {
        when(repo.browseCards(eq(null), any()))
            .thenReturn(Page.empty());

        service.browse(null, GroupsSortKey.MOST_ACTIVE_LISTINGS, PageRequest.of(0, 20));

        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> captor =
            org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        org.mockito.Mockito.verify(repo).browseCards(eq(null), captor.capture());
        Sort sort = captor.getValue().getSort();
        // The property is wrapped in parens so Hibernate emits the raw
        // expression in the native-query ORDER BY (otherwise the property
        // gets prefixed with the root table alias which collides with the
        // SELECT alias). The wire-level property string is
        // {@code (activeListings)}.
        assertThat(sort.getOrderFor("(activeListings)")).isNotNull();
        assertThat(sort.getOrderFor("(activeListings)").getDirection())
            .isEqualTo(Sort.Direction.DESC);
    }

    private static RealtyGroupCardProjection projection(String name, String description) {
        return new RealtyGroupCardProjection() {
            public UUID getPublicId() {
                return UUID.fromString("00000000-0000-0000-0000-000000000001");
            }
            public String getName() { return name; }
            public String getSlug() { return name; }
            public String getDescription() { return description; }
            public String getLogoObjectKey() { return null; }
            public String getCoverObjectKey() { return null; }
            public Instant getCreatedAt() {
                return Instant.parse("2026-01-01T00:00:00Z");
            }
            public int getMemberCount() { return 1; }
            public int getMemberSeatLimit() { return 8; }
            public long getActiveListings() { return 0L; }
            public long getCompletedSales() { return 0L; }
            public Double getAverageRating() { return null; }
            public long getReviewCount() { return 0L; }
        };
    }
}
