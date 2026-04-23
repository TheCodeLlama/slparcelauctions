package com.slparcelauctions.backend.auction.broadcast;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-support implementation of {@link AuctionBroadcastPublisher} that
 * stores every published envelope in thread-safe lists. Swap this into a
 * {@code @SpringBootTest} slice via a {@code @TestConfiguration} with a
 * {@code @Primary} bean definition when richer envelope-field assertions
 * are useful than a plain Mockito mock.
 *
 * <p>Using {@link CopyOnWriteArrayList} keeps the publisher safe to call
 * from the scheduler thread and the bid-placement thread concurrently —
 * tests exercising race scenarios can rely on iteration not throwing a
 * {@code ConcurrentModificationException}.
 *
 * <p>Example wiring:
 * <pre>
 *   &#064;TestConfiguration
 *   static class CapturingConfig {
 *     &#064;Bean &#064;Primary
 *     CapturingAuctionBroadcastPublisher capturingPublisher() {
 *       return new CapturingAuctionBroadcastPublisher();
 *     }
 *   }
 *
 *   &#064;Import(CapturingConfig.class)
 *   class MyTest {
 *     &#064;Autowired CapturingAuctionBroadcastPublisher publisher;
 *
 *     &#064;Test void foo() {
 *       bidService.placeBid(...);
 *       assertThat(publisher.settlements).hasSize(1);
 *       assertThat(publisher.settlements.get(0).currentBid()).isEqualTo(550L);
 *     }
 *   }
 * </pre>
 */
public class CapturingAuctionBroadcastPublisher implements AuctionBroadcastPublisher {

    public final List<BidSettlementEnvelope> settlements = new CopyOnWriteArrayList<>();
    public final List<AuctionEndedEnvelope> ended = new CopyOnWriteArrayList<>();

    @Override
    public void publishSettlement(BidSettlementEnvelope envelope) {
        settlements.add(envelope);
    }

    @Override
    public void publishEnded(AuctionEndedEnvelope envelope) {
        ended.add(envelope);
    }

    /** Clears both capture lists; useful in {@code @BeforeEach} hooks. */
    public void reset() {
        settlements.clear();
        ended.clear();
    }
}
