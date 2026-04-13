package com.slparcelauctions.backend.wstest;

import com.slparcelauctions.backend.auth.AuthPrincipal;
import com.slparcelauctions.backend.auth.JwtService;
import com.slparcelauctions.backend.wstest.dto.WsTestBroadcastRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WsTestIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private WebSocketStompClient stompClient;
    private BlockingQueue<Map<String, Object>> receivedMessages;

    @BeforeEach
    void setUp() {
        List<Transport> transports = List.of(new WebSocketTransport(new StandardWebSocketClient()));
        SockJsClient sockJsClient = new SockJsClient(transports);
        stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        receivedMessages = new LinkedBlockingQueue<>();
    }

    @AfterEach
    void tearDown() {
        if (stompClient != null) {
            stompClient.stop();
        }
    }

    private String issueAccessTokenForTestUser() {
        AuthPrincipal principal = new AuthPrincipal(9999L, "wstest@example.com", 1L);
        return jwtService.issueAccessToken(principal);
    }

    private String wsUrl() {
        return "http://localhost:" + port + "/ws";
    }

    @Test
    void stompConnectWithValidToken_receivesBroadcast() throws Exception {
        String token = issueAccessTokenForTestUser();

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        stompClient.connectAsync(
            wsUrl(),
            new WebSocketHttpHeaders(),
            connectHeaders,
            new StompSessionHandlerAdapter() {
                @Override
                public void afterConnected(StompSession session, StompHeaders headers) {
                    sessionFuture.complete(session);
                }

                @Override
                public void handleException(StompSession session, StompCommand command,
                                            StompHeaders headers, byte[] payload, Throwable exception) {
                    sessionFuture.completeExceptionally(exception);
                }

                @Override
                public void handleTransportError(StompSession session, Throwable exception) {
                    if (!sessionFuture.isDone()) {
                        sessionFuture.completeExceptionally(exception);
                    }
                }
            }
        );

        StompSession session = sessionFuture.get(5, TimeUnit.SECONDS);

        session.subscribe("/topic/ws-test", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                receivedMessages.offer((Map<String, Object>) payload);
            }
        });

        // Tiny pause to let the subscription register before we broadcast.
        Thread.sleep(200);

        // Trigger the broadcast via the HTTP endpoint.
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setBearerAuth(token);
        httpHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        HttpEntity<WsTestBroadcastRequest> request =
            new HttpEntity<>(new WsTestBroadcastRequest("hello from test"), httpHeaders);

        ResponseEntity<Void> response = restTemplate.exchange(
            "http://localhost:" + port + "/api/ws-test/broadcast",
            HttpMethod.POST,
            request,
            Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Map<String, Object> received = receivedMessages.poll(5, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.get("message")).isEqualTo("hello from test");
        assertThat(received.get("senderId")).isEqualTo(9999);
        assertThat(received.get("timestamp")).isNotNull();
    }

    @Test
    void stompConnectWithoutAuthHeader_isRejected() {
        // No Authorization header in connectHeaders — interceptor throws
        // MessagingException which surfaces as a connect failure.
        StompHeaders connectHeaders = new StompHeaders();

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        stompClient.connectAsync(
            wsUrl(),
            new WebSocketHttpHeaders(),
            connectHeaders,
            new StompSessionHandlerAdapter() {
                @Override
                public void afterConnected(StompSession session, StompHeaders headers) {
                    sessionFuture.complete(session);
                }

                @Override
                public void handleException(StompSession session, StompCommand command,
                                            StompHeaders headers, byte[] payload, Throwable exception) {
                    sessionFuture.completeExceptionally(exception);
                }

                @Override
                public void handleTransportError(StompSession session, Throwable exception) {
                    if (!sessionFuture.isDone()) {
                        sessionFuture.completeExceptionally(exception);
                    }
                }
            }
        );

        assertThatThrownBy(() -> sessionFuture.get(5, TimeUnit.SECONDS))
            .isInstanceOfAny(ExecutionException.class, TimeoutException.class);
    }

    @Test
    void stompConnectWithInvalidToken_isRejected() {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer not-a-real-jwt");

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        stompClient.connectAsync(
            wsUrl(),
            new WebSocketHttpHeaders(),
            connectHeaders,
            new StompSessionHandlerAdapter() {
                @Override
                public void afterConnected(StompSession session, StompHeaders headers) {
                    sessionFuture.complete(session);
                }

                @Override
                public void handleException(StompSession session, StompCommand command,
                                            StompHeaders headers, byte[] payload, Throwable exception) {
                    sessionFuture.completeExceptionally(exception);
                }

                @Override
                public void handleTransportError(StompSession session, Throwable exception) {
                    if (!sessionFuture.isDone()) {
                        sessionFuture.completeExceptionally(exception);
                    }
                }
            }
        );

        assertThatThrownBy(() -> sessionFuture.get(5, TimeUnit.SECONDS))
            .isInstanceOfAny(ExecutionException.class, TimeoutException.class);
    }
}
