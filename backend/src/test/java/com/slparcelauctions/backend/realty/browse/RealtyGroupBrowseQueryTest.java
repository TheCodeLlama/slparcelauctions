package com.slparcelauctions.backend.realty.browse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.slparcelauctions.backend.realty.RealtyGroupRepository;

@SpringBootTest
@ActiveProfiles("dev")
@Transactional
@TestPropertySource(properties = {
    "auth.cleanup.enabled=false",
    "slpa.auction-end.enabled=false",
    "slpa.ownership-monitor.enabled=false",
    "slpa.notifications.cleanup.enabled=false",
    "slpa.notifications.sl-im.cleanup.enabled=false",
    "slpa.realty.invitation-expiry.enabled=false"
})
class RealtyGroupBrowseQueryTest {

    @Autowired RealtyGroupRepository repo;

    @Test
    void returnsEmptyPageWhenSearchMatchesNothing() {
        // Use a guaranteed-no-match search term so the test is robust against
        // any seeded fixtures in the dev DB. Exercises the full SELECT (not
        // just the count short-circuit), so a SQL/projection-shape regression
        // surfaces here.
        Page<RealtyGroupCardProjection> page = repo.browseCards(
            "ZZ_definitely_not_a_realty_group_name_XYZ_QQ",
            0.0, 0, false,
            PageRequest.of(0, 20, Sort.unsorted()));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void browseCardsReturnsAPageObjectForAnUnfilteredQuery() {
        // Smoke test: the unfiltered call returns a non-null Page. Content
        // depends on dev-DB state, so we don't assert on count -- only that
        // the projection binds cleanly (no MissingPropertyException on any
        // getter) by touching every getter on every row.
        Page<RealtyGroupCardProjection> page = repo.browseCards(
            null,
            0.0, 0, false,
            PageRequest.of(0, 20, Sort.unsorted()));

        assertThat(page).isNotNull();
        for (RealtyGroupCardProjection row : page.getContent()) {
            assertThat(row.getPublicId()).isNotNull();
            assertThat(row.getName()).isNotNull();
            assertThat(row.getSlug()).isNotNull();
            row.getDescription();
            row.getLogoLightObjectKey();
            row.getLogoDarkObjectKey();
            row.getCoverLightObjectKey();
            row.getCoverDarkObjectKey();
            row.getDefaultListingLightObjectKey();
            row.getDefaultListingDarkObjectKey();
            assertThat(row.getCreatedAt()).isNotNull();
            row.getMemberCount();
            row.getMemberSeatLimit();
            row.getActiveListings();
            row.getCompletedSales();
            row.getAverageRating();
            row.getReviewCount();
        }
    }

    @Test
    void filterParamsBindCleanlyEvenWhenResultsAreEmpty() {
        // Reuse the guaranteed-no-match `q` so the dev DB does not change
        // the assertion, while exercising every optional filter binding.
        Page<RealtyGroupCardProjection> page = repo.browseCards(
            "ZZ_definitely_not_a_realty_group_name_XYZ_QQ",
            4.5, 25, true,
            PageRequest.of(0, 20, Sort.unsorted()));

        assertThat(page).isNotNull();
        assertThat(page.getContent()).isEmpty();
    }
}
