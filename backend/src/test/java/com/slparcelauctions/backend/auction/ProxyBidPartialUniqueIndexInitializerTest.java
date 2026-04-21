package com.slparcelauctions.backend.auction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Asserts {@link ProxyBidPartialUniqueIndexInitializer} fired during context
 * startup by querying {@code pg_indexes} for the expected index name. Also
 * confirms the index definition carries the partial predicate so any future
 * accidental rewrite of the DDL surfaces here rather than at the first
 * DB-level collision in production.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = "auth.cleanup.enabled=false")
class ProxyBidPartialUniqueIndexInitializerTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void partialUniqueIndexExistsOnProxyBids() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'proxy_bids'",
                String.class);

        assertThat(indexes).contains("proxy_bids_one_active_per_user");
    }

    @Test
    void partialUniqueIndexHasActiveStatusPredicate() {
        String indexDef = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes "
                        + "WHERE tablename = 'proxy_bids' "
                        + "AND indexname = 'proxy_bids_one_active_per_user'",
                String.class);

        assertThat(indexDef).isNotNull();
        // Postgres canonicalises the DDL but keeps the WHERE clause and UNIQUE
        // keyword visible — both must be present for the invariant to hold.
        assertThat(indexDef).containsIgnoringCase("UNIQUE INDEX");
        assertThat(indexDef).containsIgnoringCase("WHERE");
        assertThat(indexDef).contains("'ACTIVE'");
    }
}
