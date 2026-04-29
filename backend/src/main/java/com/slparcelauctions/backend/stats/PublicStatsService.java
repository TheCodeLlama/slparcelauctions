package com.slparcelauctions.backend.stats;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Computes the bundled public-stats DTO. All four queries run together
 * on a cache miss so the snapshot is internally consistent — counts and
 * the {@code activeBidTotalL} sum reflect the same instant rather than
 * a smear across multiple compute passes.
 *
 * <p>Status literals come straight from
 * {@link com.slparcelauctions.backend.auction.AuctionStatus}: ACTIVE for
 * the live counts, COMPLETED for the Epic 05 terminal "successful sale"
 * state.
 */
@Service
@RequiredArgsConstructor
public class PublicStatsService {

    private final JdbcTemplate jdbc;
    private final PublicStatsCache cache;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PublicStatsDto get() {
        return cache.getOrCompute(this::compute);
    }

    private PublicStatsDto compute() {
        Long activeAuctions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM auctions WHERE status = 'ACTIVE'", Long.class);
        Long activeBidTotal = jdbc.queryForObject(
                "SELECT COALESCE(SUM(current_bid), 0) FROM auctions WHERE status = 'ACTIVE'",
                Long.class);
        Long completedSales = jdbc.queryForObject(
                "SELECT COUNT(*) FROM auctions WHERE status = 'COMPLETED'", Long.class);
        Long registeredUsers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users", Long.class);
        return new PublicStatsDto(
                nullTo0(activeAuctions),
                nullTo0(activeBidTotal),
                nullTo0(completedSales),
                nullTo0(registeredUsers),
                OffsetDateTime.now(clock));
    }

    private static long nullTo0(Long v) {
        return v == null ? 0L : v;
    }
}
