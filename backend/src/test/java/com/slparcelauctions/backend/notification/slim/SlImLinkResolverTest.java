package com.slparcelauctions.backend.notification.slim;

import static org.assertj.core.api.Assertions.assertThat;

import com.slparcelauctions.backend.config.SlpaWebProperties;
import com.slparcelauctions.backend.notification.NotificationCategory;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlImLinkResolverTest {

    private static final String BASE = "https://slpa.example.com";
    private final SlImLinkResolver resolver = new SlImLinkResolver(new SlpaWebProperties(BASE));

    @Test
    void outbid_resolvesToAuctionPage() {
        assertThat(resolver.resolve(NotificationCategory.OUTBID, Map.of("auctionId", 42)))
            .isEqualTo(BASE + "/auction/42");
    }

    @Test
    void auctionWon_resolvesToEscrowPage() {
        assertThat(resolver.resolve(NotificationCategory.AUCTION_WON, Map.of("auctionId", 42)))
            .isEqualTo(BASE + "/auction/42/escrow");
    }

    @Test
    void escrowFunded_resolvesToEscrowPage() {
        assertThat(resolver.resolve(NotificationCategory.ESCROW_FUNDED,
                Map.of("auctionId", 42, "escrowId", 100)))
            .isEqualTo(BASE + "/auction/42/escrow");
    }

    @Test
    void listingSuspended_resolvesToDashboardListings() {
        assertThat(resolver.resolve(NotificationCategory.LISTING_SUSPENDED,
                Map.of("auctionId", 42, "reason", "test")))
            .isEqualTo(BASE + "/dashboard/listings");
    }

    @Test
    void systemAnnouncement_resolvesToNotificationsFeed() {
        assertThat(resolver.resolve(NotificationCategory.SYSTEM_ANNOUNCEMENT, Map.of()))
            .isEqualTo(BASE + "/notifications");
    }

    @Test
    void everyCategory_producesAValidUrl() {
        // Every category must produce a URL starting with the base; ensures the
        // switch covers all enum values (compile-time guarantee + runtime sanity).
        Map<String, Object> data = Map.ofEntries(
            Map.entry("auctionId", 42),
            Map.entry("escrowId", 100),
            Map.entry("reviewId", 5),
            Map.entry("reason", "x"),
            Map.entry("parcelName", "P"),
            Map.entry("currentBidL", 1L),
            Map.entry("isProxyOutbid", false),
            Map.entry("endsAt", "2026-04-26T18:00:00Z"),
            Map.entry("winningBidL", 1L),
            Map.entry("highestBidL", 1L),
            Map.entry("buyNowL", 1L),
            Map.entry("transferDeadline", "2026-04-26T18:00:00Z"),
            Map.entry("payoutL", 1L),
            Map.entry("reasonCategory", "x"),
            Map.entry("rating", 5)
        );
        for (NotificationCategory c : NotificationCategory.values()) {
            String url = resolver.resolve(c, data);
            assertThat(url).startsWith(BASE);
        }
    }
}
