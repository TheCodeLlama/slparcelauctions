package com.slparcelauctions.backend.auction.broadcast;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import com.slparcelauctions.backend.auction.Auction;
import com.slparcelauctions.backend.auction.AuctionRepository;
import com.slparcelauctions.backend.auction.AuctionStatus;
import com.slparcelauctions.backend.auction.BidRepository;
import com.slparcelauctions.backend.auction.BidService;
import com.slparcelauctions.backend.auction.VerificationMethod;
import com.slparcelauctions.backend.auction.VerificationTier;
import com.slparcelauctions.backend.auction.AuctionParcelSnapshot;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * End-to-end WebSocket broadcast test — connects a real STOMP client to
 * {@code /ws}, subscribes to {@code /topic/auction/{publicId}} without
 * authentication (per sub-spec §4 the topic is public), places a bid via
 * {@link BidService#placeBid}, and asserts the
 * {@link BidSettlementEnvelope} is delivered within 2 seconds of commit.
 *
 * <p><strong>Not {@code @Transactional}:</strong> the bid-placement
 * transaction must actually commit so the {@code afterCommit} callback
 * fires. An ambient test transaction rolls back, suppressing the publish
 * and hanging the subscriber.
 *
 * <p><strong>Why {@code webEnvironment = RANDOM_PORT}:</strong> the STOMP
 * client needs a real Tomcat listener; the default {@code MOCK} env does
 * not bind a port. {@link DirtiesContext} releases the port after the
 * class runs so concurrent test classes don't collide.
 *
 * <p><strong>Anonymous CONNECT:</strong> the test opens a CONNECT frame
 * without an {@code Authorization} header. The
 * {@code JwtChannelInterceptor} allows anonymous CONNECTs and gates each
 * SUBSCRIBE against a public-prefix allowlist — {@code /topic/auction/**}
 * is on that list.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BidWebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired BidService bidService;
    @Autowired AuctionRepository auctionRepository;
    @Autowired BidRepository bidRepository;
    @Autowired UserRepository userRepository;
    @Autowired PlatformTransactionManager txManager;

    private WebSocketStompClient stompClient;
    private StompSession session;

    private Long sellerId;
    private Long bidderId;
    private Long auctionId;
    private UUID auctionPublicId;
    private UUID bidderPublicId;

    @AfterEach
    void tearDown() {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
        if (stompClient != null) {
            stompClient.stop();
        }
        cleanupFixtures();
    }

    @Test
    void placeBid_deliversBidSettlementEnvelopeToSubscribers() throws Exception {
        seedFixtures();

        BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();
        session = connectAnonymously();
        session.subscribe("/topic/auction/" + auctionPublicId, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                // Spring's default Jackson converter needs a concrete type
                // hint. The STOMP message arrives as JSON — decode into a
                // generic Map so the test does not depend on the runtime
                // classpath exposing BidSettlementEnvelope as a
                // deserialization target. Field-level assertions are done
                // by key lookup below.
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                received.offer((Map<String, Object>) payload);
            }
        });

        // Tiny pause to let the SUBSCRIBE frame register with the broker
        // before we publish. Without this, convertAndSend can race the
        // SUBSCRIBE and the subscriber misses the first frame.
        Thread.sleep(200);

        // Drive a bid through the full transactional path. placeBid wraps
        // the work in @Transactional and registers the afterCommit publish,
        // so the envelope only surfaces after the row is durably committed.
        bidService.placeBid(auctionId, bidderId, 1_000L, "1.2.3.4");

        Map<String, Object> envelope = received.poll(5, TimeUnit.SECONDS);
        assertThat(envelope)
                .as("BidSettlementEnvelope must arrive on /topic/auction/%s within 5s", auctionPublicId)
                .isNotNull();
        assertThat(envelope.get("type")).isEqualTo("BID_SETTLEMENT");
        assertThat(envelope.get("auctionPublicId")).isEqualTo(auctionPublicId.toString());
        assertThat(((Number) envelope.get("currentBid")).longValue()).isEqualTo(1_000L);
        assertThat(envelope.get("currentBidderPublicId")).isEqualTo(bidderPublicId.toString());
        assertThat(envelope.get("currentBidderDisplayName")).isEqualTo("WS Bidder");
        assertThat(((Number) envelope.get("bidCount")).intValue()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> newBids = (List<Map<String, Object>>) envelope.get("newBids");
        assertThat(newBids).hasSize(1);
        assertThat(((Number) newBids.get(0).get("amount")).longValue()).isEqualTo(1_000L);
        assertThat(newBids.get(0).get("bidType")).isEqualTo("MANUAL");
        assertThat(newBids.get(0).get("bidderDisplayName")).isEqualTo("WS Bidder");
    }

    // ---------------------------------------------------------------------
    // STOMP wiring
    // ---------------------------------------------------------------------

    private StompSession connectAnonymously() throws Exception {
        List<Transport> transports = List.of(new WebSocketTransport(new StandardWebSocketClient()));
        SockJsClient sockJsClient = new SockJsClient(transports);
        stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        stompClient.connectAsync(
                "http://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(),
                new StompHeaders(),
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession s, StompHeaders headers) {
                        sessionFuture.complete(s);
                    }

                    @Override
                    public void handleException(StompSession s, StompCommand cmd,
                                                StompHeaders headers, byte[] payload,
                                                Throwable exception) {
                        sessionFuture.completeExceptionally(exception);
                    }

                    @Override
                    public void handleTransportError(StompSession s, Throwable exception) {
                        if (!sessionFuture.isDone()) {
                            sessionFuture.completeExceptionally(exception);
                        }
                    }
                });

        return sessionFuture.get(5, TimeUnit.SECONDS);
    }

    // ---------------------------------------------------------------------
    // Fixture management — committed OUTSIDE the test so the bid-placement
    // transaction can see the rows. Deleted in @AfterEach.
    // ---------------------------------------------------------------------

    private void seedFixtures() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            User seller = userRepository.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("ws-seller-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("WS Seller")
                    .verified(true)
                    .slAvatarUuid(UUID.randomUUID())
                    .build());
            User bidder = userRepository.save(User.builder().username("u-" + UUID.randomUUID().toString().substring(0, 8))
                    .email("ws-bidder-" + UUID.randomUUID() + "@example.com")
                    .passwordHash("$2a$10$dummy.hash.value.for.test.only.aaaaaaaaaaaaaaaaaaaa")
                    .displayName("WS Bidder")
                    .verified(true)
                    .slAvatarUuid(UUID.randomUUID())
                    .build());
            UUID parcelUuid = UUID.randomUUID();
            UUID ownerUuid = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now();
            Auction auction = auctionRepository.save(Auction.builder()
                    .title("Test listing")
                    .slParcelUuid(parcelUuid)
                    .seller(seller)
                    .status(AuctionStatus.ACTIVE)
                    .verificationMethod(VerificationMethod.UUID_ENTRY)
                    .verificationTier(VerificationTier.SCRIPT)
                    .startingBid(1_000L)
                    .durationHours(168)
                    .snipeProtect(false)
                    .listingFeePaid(true)
                    .currentBid(0L)
                    .bidCount(0)
                    .consecutiveWorldApiFailures(0)
                    .startsAt(now.minusHours(1))
                    .endsAt(now.plusDays(1))
                    .originalEndsAt(now.plusDays(1))
                    .commissionRate(new BigDecimal("0.05"))
                    .agentFeeRate(BigDecimal.ZERO)
                    .build());

            auction.setParcelSnapshot(AuctionParcelSnapshot.builder()
                    .slParcelUuid(parcelUuid)
                    .ownerUuid(ownerUuid)
                    .ownerType("agent")
                    .parcelName("WS Test Parcel")
                    .regionName("Test Region")
                    .regionMaturityRating("GENERAL")
                    .areaSqm(1024)
                    .positionX(128.0).positionY(64.0).positionZ(22.0)
                    .build());
            auctionRepository.save(auction);

            this.sellerId = seller.getId();
            this.bidderId = bidder.getId();
            this.bidderPublicId = bidder.getPublicId();
            this.auctionId = auction.getId();
            this.auctionPublicId = auction.getPublicId();
        });
    }

    private void cleanupFixtures() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            if (auctionId != null) {
                auctionRepository.findById(auctionId).ifPresent(a -> {
                    bidRepository.findByAuctionIdOrderByCreatedAtAsc(a.getId())
                            .forEach(bidRepository::delete);
                    auctionRepository.delete(a);
                });
            }
            if (bidderId != null) {
                userRepository.findById(bidderId).ifPresent(userRepository::delete);
            }
            if (sellerId != null) {
                userRepository.findById(sellerId).ifPresent(userRepository::delete);
            }
        });
        auctionId = null;
        auctionPublicId = null;
        bidderId = null;
        bidderPublicId = null;
        sellerId = null;
    }
}
