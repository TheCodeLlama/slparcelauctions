package com.slparcelauctions.backend.auction.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
class PgIndexExistenceTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void every_promised_index_exists() {
        Set<String> required = Set.of(
                "ix_auctions_status_ends_at",
                "ix_auctions_status_starts_at",
                "ix_auctions_status_current_bid",
                "ix_auctions_seller_status",
                "ix_auctions_status_reserve",
                "ix_parcels_region",
                "ix_parcels_area_sqm",
                "ix_regions_grid_coords",
                "ix_regions_maturity",
                "ix_auction_tags_tag_id",
                // Task 7: saved-auctions composite read index + per-user uniqueness
                // (Postgres backs UNIQUE constraints with a same-named btree index).
                "ix_saved_auctions_user_saved_at",
                "uk_saved_auctions_user_auction"
        );

        Set<String> actual = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'",
                String.class).stream().collect(Collectors.toSet());

        List<String> missing = required.stream()
                .filter(ix -> !actual.contains(ix))
                .sorted()
                .toList();

        assertThat(missing)
                .as("indexes promised by Epic 07 sub-spec 1 missing from live schema")
                .isEmpty();
    }
}
