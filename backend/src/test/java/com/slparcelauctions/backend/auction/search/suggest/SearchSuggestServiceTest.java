package com.slparcelauctions.backend.auction.search.suggest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SearchSuggestServiceTest {

    @Mock SearchSuggestRepository repo;
    @InjectMocks SearchSuggestService service;

    @Test
    void capsListingsAt5_andRegionsAt3() {
        when(repo.findListings("foo", 5)).thenReturn(List.of(
                listing("a"), listing("b"), listing("c"), listing("d"), listing("e")));
        when(repo.findRegions("foo", 3)).thenReturn(List.of(
                new SuggestRegionDto("Tula", 4),
                new SuggestRegionDto("Luna", 2),
                new SuggestRegionDto("Terra", 1)));
        when(repo.countListings("foo")).thenReturn(12);

        SuggestResponse r = service.suggest("foo");
        assertThat(r.listings()).hasSize(5);
        assertThat(r.regions()).hasSize(3);
        assertThat(r.totalListings()).isEqualTo(12);
    }

    @Test
    void totalListings_isRepositoryCount_notListSize() {
        when(repo.findListings("foo", 5)).thenReturn(List.of(listing("a")));
        when(repo.findRegions("foo", 3)).thenReturn(List.of());
        when(repo.countListings("foo")).thenReturn(42);

        SuggestResponse r = service.suggest("foo");
        assertThat(r.listings()).hasSize(1);
        assertThat(r.totalListings()).isEqualTo(42);
    }

    @Test
    void regionsOnly_skipsListings_andSourcesResolvableRegions() {
        when(repo.findResolvableRegions("tul", 10)).thenReturn(List.of(
                new SuggestRegionDto("Tula", 0),
                new SuggestRegionDto("Tula Beach", 0)));

        SuggestResponse r = service.suggestRegionsOnly("tul");

        assertThat(r.listings()).isEmpty();
        assertThat(r.totalListings()).isZero();
        assertThat(r.regions()).extracting(SuggestRegionDto::name)
                .containsExactly("Tula", "Tula Beach");
        // The listing queries must not run in region-only mode.
        verifyNoMoreInteractions(repo);
    }

    private static SuggestListingDto listing(String title) {
        return new SuggestListingDto(
                UUID.randomUUID(), title, "Tula",
                "Parcel", null, 1000L);
    }
}
