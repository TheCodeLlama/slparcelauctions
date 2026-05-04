package com.slparcelauctions.backend.notification.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

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
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.user.Role;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.notification.NotificationCategory;
import com.slparcelauctions.backend.notification.NotificationDao;
import com.slparcelauctions.backend.notification.NotificationGroup;
import com.slparcelauctions.backend.notification.dto.NotificationDto;
import com.slparcelauctions.backend.user.User;
import com.slparcelauctions.backend.user.UserRepository;

/**
 * End-to-end security regression test: verifies that per-user STOMP queue
 * routing does NOT leak messages between users. If this test ever fails,
 * that is a cross-user data leakage bug — treat as a security incident.
 *
 * <p>Also verifies that anonymous sessions cannot subscribe to
 * {@code /user/queue/**} destinations — the {@link com.slparcelauctions.backend.auth.JwtChannelInterceptor}
 * blocks SUBSCRIBE from non-authenticated sessions for anything outside the
 * public auction destination allowlist.
 *
 * <p>{@code @DirtiesContext} ensures the Tomcat port is released after the
 * class so concurrent test classes do not collide.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
        "auth.cleanup.enabled=false",
        "slpa.auction-end.enabled=false",
        "slpa.ownership-monitor.enabled=false",
        "slpa.escrow.ownership-monitor-job.enabled=false",
        "slpa.escrow.timeout-job.enabled=false",
        "slpa.escrow.command-dispatcher-job.enabled=false",
        "slpa.review.scheduler.enabled=false",
        "slpa.notifications.cleanup.enabled=false",
        "slpa.notifications.sl-im.cleanup.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserQueueRoutingTest {

    @LocalServerPort
    private int port;

    @Autowired
    NotificationWsBroadcaster broadcaster;

    @Autowired
    UserRepository userRepo;

    @Autowired
    JwtService jwtService;

    @Autowired
    DataSource dataSource;

    private final java.util.List<Long> createdUserIds = new java.util.ArrayList<>();
    private StompSession aliceSession;
    private StompSession bobSession;
    private StompSession anonSession;

    @AfterEach
    void tearDown() throws Exception {
        disconnectQuietly(aliceSession);
        disconnectQuietly(bobSession);
        disconnectQuietly(anonSession);
        aliceSession = null;
        bobSession = null;
        anonSession = null;

        if (!createdUserIds.isEmpty()) {
            try (var conn = dataSource.getConnection()) {
                conn.setAutoCommit(true);
                try (var stmt = conn.createStatement()) {
                    for (Long id : createdUserIds) {
                        if (id != null) {
                            stmt.execute("DELETE FROM notification WHERE user_id = " + id);
                            stmt.execute("DELETE FROM refresh_tokens WHERE user_id = " + id);
                            stmt.execute("DELETE FROM users WHERE id = " + id);
                        }
                    }
                }
            }
            createdUserIds.clear();
        }
    }

    // ── security regression: cross-user leakage guard ─────────────────────────

    /**
     * Broadcasts to alice; asserts alice receives the message and bob does NOT.
     * If bob ever receives alice's message, user-destination routing is broken
     * and this is a security-critical failure.
     */
    @Test
    void userA_doesNotReceiveUserB_notifications() throws Exception {
        User alice = saveUser("routing-alice");
        User bob = saveUser("routing-bob");

        String aliceJwt = issueJwt(alice);
        String bobJwt = issueJwt(bob);

        aliceSession = connectAuthenticated(aliceJwt);
        bobSession = connectAuthenticated(bobJwt);

        var aliceQueue = new LinkedBlockingQueue<Object>();
        var bobQueue = new LinkedBlockingQueue<Object>();

        aliceSession.subscribe("/user/queue/notifications", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders h) { return Object.class; }
            @Override
            public void handleFrame(StompHeaders h, Object p) { aliceQueue.offer(p); }
        });
        bobSession.subscribe("/user/queue/notifications", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders h) { return Object.class; }
            @Override
            public void handleFrame(StompHeaders h, Object p) { bobQueue.offer(p); }
        });

        // Allow subscriptions to register with the broker before broadcasting.
        Thread.sleep(200);

        // Backend broadcasts to alice's userId only.
        UUID notifPublicId = UUID.randomUUID();
        var dto = new NotificationDto(notifPublicId, NotificationCategory.OUTBID,
                NotificationGroup.BIDDING, "title", "body", Map.of(),
                false, OffsetDateTime.now(), OffsetDateTime.now());
        var result = new NotificationDao.UpsertResult(notifPublicId, false, OffsetDateTime.now(), OffsetDateTime.now());
        broadcaster.broadcastUpsert(alice.getId(), result, dto);

        // Alice MUST receive within 2s.
        Object aliceMsg = aliceQueue.poll(2, TimeUnit.SECONDS);
        assertThat(aliceMsg)
                .as("alice should receive her own notification within 2s")
                .isNotNull();

        // Bob MUST NOT receive anything — this is the security assertion.
        Object bobMsg = bobQueue.poll(500, TimeUnit.MILLISECONDS);
        assertThat(bobMsg)
                .as("bob MUST NOT receive alice's notification (cross-user leakage would be a security bug)")
                .isNull();
    }

    // ── anonymous session gate ────────────────────────────────────────────────

    /**
     * An anonymous STOMP session may CONNECT (JwtChannelInterceptor allows it)
     * but may NOT subscribe to {@code /user/queue/**}. The interceptor throws a
     * {@link org.springframework.messaging.MessagingException} which causes the
     * broker to send an ERROR frame and close the session.
     *
     * <p>We verify that after subscribing to the protected destination, the
     * session is no longer connected — either the session was killed by the ERROR
     * frame, or (if the broker defers disconnect) the subscription queue receives
     * nothing when we broadcast to a real user.
     */
    @Test
    void unauthenticatedSession_cannotSubscribeToUserQueue() throws Exception {
        anonSession = connectAnonymous();
        assertThat(anonSession.isConnected())
                .as("anonymous session should be connected before attempting protected subscribe")
                .isTrue();

        // Track whether any message leaks through to the anon subscriber.
        var anonQueue = new LinkedBlockingQueue<Object>();

        // Subscribing to /user/queue/notifications as anonymous MUST be rejected.
        // The JwtChannelInterceptor throws MessagingException → STOMP ERROR frame → session killed.
        anonSession.subscribe("/user/queue/notifications", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders h) { return Object.class; }
            @Override
            public void handleFrame(StompHeaders h, Object p) { anonQueue.offer(p); }
        });

        // Give the broker time to process the SUBSCRIBE and deliver the ERROR frame.
        Thread.sleep(500);

        // The session should be dead — the ERROR frame closes it.
        assertThat(anonSession.isConnected())
                .as("anonymous session must be disconnected after subscribing to a protected destination")
                .isFalse();

        // Belt-and-suspenders: even if the session somehow stayed alive, nothing should arrive.
        Object leaked = anonQueue.poll(300, TimeUnit.MILLISECONDS);
        assertThat(leaked)
                .as("anonymous session must not receive any messages from protected queue")
                .isNull();
    }

    // ── STOMP connection helpers ──────────────────────────────────────────────

    private StompSession connectAuthenticated(String jwtToken) throws Exception {
        List<Transport> transports = List.of(new WebSocketTransport(new StandardWebSocketClient()));
        SockJsClient sockJsClient = new SockJsClient(transports);
        var client = new org.springframework.web.socket.messaging.WebSocketStompClient(sockJsClient);
        client.setMessageConverter(new MappingJackson2MessageConverter());

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.set("Authorization", "Bearer " + jwtToken);

        CompletableFuture<StompSession> future = new CompletableFuture<>();
        client.connectAsync(
                "http://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(),
                connectHeaders,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession s, StompHeaders headers) {
                        future.complete(s);
                    }

                    @Override
                    public void handleException(StompSession s, StompCommand cmd,
                                                StompHeaders headers, byte[] payload,
                                                Throwable exception) {
                        future.completeExceptionally(exception);
                    }

                    @Override
                    public void handleTransportError(StompSession s, Throwable exception) {
                        if (!future.isDone()) {
                            future.completeExceptionally(exception);
                        }
                    }
                });

        return future.get(5, TimeUnit.SECONDS);
    }

    private StompSession connectAnonymous() throws Exception {
        List<Transport> transports = List.of(new WebSocketTransport(new StandardWebSocketClient()));
        SockJsClient sockJsClient = new SockJsClient(transports);
        var client = new org.springframework.web.socket.messaging.WebSocketStompClient(sockJsClient);
        client.setMessageConverter(new MappingJackson2MessageConverter());

        CompletableFuture<StompSession> future = new CompletableFuture<>();
        client.connectAsync(
                "http://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(),
                new StompHeaders(),
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession s, StompHeaders headers) {
                        future.complete(s);
                    }

                    @Override
                    public void handleException(StompSession s, StompCommand cmd,
                                                StompHeaders headers, byte[] payload,
                                                Throwable exception) {
                        future.completeExceptionally(exception);
                    }

                    @Override
                    public void handleTransportError(StompSession s, Throwable exception) {
                        // Anonymous session may get an ERROR frame and transport-error;
                        // complete the future only if not already done (we want the
                        // connected session handle so we can observe the subsequent kill).
                        if (!future.isDone()) {
                            future.completeExceptionally(exception);
                        }
                    }
                });

        return future.get(5, TimeUnit.SECONDS);
    }

    // ── data helpers ─────────────────────────────────────────────────────────

    private User saveUser(String prefix) {
        User u = userRepo.save(User.builder()
                .email(prefix + "-" + UUID.randomUUID() + "@example.com")
                .passwordHash("hash")
                .build());
        createdUserIds.add(u.getId());
        return u;
    }

    private String issueJwt(User user) {
        return jwtService.issueAccessToken(
                new AuthPrincipal(user.getId(), user.getPublicId(), user.getEmail(), user.getTokenVersion(), Role.USER));
    }

    private static void disconnectQuietly(StompSession session) {
        try {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }
}
