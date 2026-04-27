package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.auth.exception.TokenExpiredException;
import com.slparcelauctions.backend.auth.exception.TokenInvalidException;
import com.slparcelauctions.backend.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class JwtChannelInterceptorTest {

    private JwtService jwtService;
    private JwtChannelInterceptor interceptor;
    private MessageChannel channel;

    @BeforeEach
    void setUp() {
        jwtService = Mockito.mock(JwtService.class);
        interceptor = new JwtChannelInterceptor(jwtService);
        channel = Mockito.mock(MessageChannel.class);
    }

    private Message<byte[]> stompMessage(StompCommand command, String authHeader) {
        return stompMessage(command, authHeader, null, null);
    }

    private Message<byte[]> stompMessage(
            StompCommand command,
            String authHeader,
            String destination,
            Principal user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (authHeader != null) {
            accessor.setNativeHeader("Authorization", authHeader);
        }
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (user != null) {
            accessor.setUser(user);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    // ------------------------------------------------------------------------
    // CONNECT-frame behaviour
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("CONNECT without Authorization header is accepted as anonymous")
    void preSend_connectFrame_missingAuthHeader_allowsAnonymous() {
        Message<byte[]> msg = stompMessage(StompCommand.CONNECT, null);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        // No principal attached → session is anonymous; SUBSCRIBE gate
        // enforces per-destination authorization downstream.
        StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);
        assertThat(resultAccessor.getUser()).isNull();
        Mockito.verifyNoInteractions(jwtService);
    }

    @Test
    @DisplayName("CONNECT with malformed (non-Bearer) Authorization header is rejected")
    void preSend_connectFrame_malformedBearer_throws() {
        Message<byte[]> msg = stompMessage(StompCommand.CONNECT, "NotBearer foo");

        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
            .isInstanceOf(MessagingException.class)
            .hasMessageContaining("Invalid Authorization header");

        Mockito.verifyNoInteractions(jwtService);
    }

    @Test
    @DisplayName("CONNECT with valid token attaches StompAuthenticationToken principal")
    void preSend_connectFrame_validToken_attachesPrincipal() {
        AuthPrincipal authPrincipal = new AuthPrincipal(42L, "test@example.com", 1L, Role.USER);
        when(jwtService.parseAccessToken("valid-jwt")).thenReturn(authPrincipal);

        Message<byte[]> msg = stompMessage(StompCommand.CONNECT, "Bearer valid-jwt");

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isNotNull();
        StompHeaderAccessor resultAccessor =
            StompHeaderAccessor.wrap(result);
        Principal user = resultAccessor.getUser();
        assertThat(user).isInstanceOf(StompAuthenticationToken.class);
        assertThat(user.getName()).isEqualTo("42");
        StompAuthenticationToken token = (StompAuthenticationToken) user;
        assertThat(token.principal().userId()).isEqualTo(42L);
        assertThat(token.principal().email()).isEqualTo("test@example.com");
        assertThat(token.principal().tokenVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("CONNECT with expired token is rejected")
    void preSend_connectFrame_expiredToken_throws() {
        when(jwtService.parseAccessToken(any()))
            .thenThrow(new TokenExpiredException("expired"));

        Message<byte[]> msg = stompMessage(StompCommand.CONNECT, "Bearer expired-jwt");

        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
            .isInstanceOf(MessagingException.class)
            .hasMessageContaining("Invalid or expired access token");
    }

    @Test
    @DisplayName("CONNECT with invalid token is rejected")
    void preSend_connectFrame_invalidToken_throws() {
        when(jwtService.parseAccessToken(any()))
            .thenThrow(new TokenInvalidException("bad token"));

        Message<byte[]> msg = stompMessage(StompCommand.CONNECT, "Bearer bad-jwt");

        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
            .isInstanceOf(MessagingException.class)
            .hasMessageContaining("Invalid or expired access token");
    }

    // ------------------------------------------------------------------------
    // SUBSCRIBE-frame authorization
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("SUBSCRIBE to /topic/auction/** is allowed for anonymous sessions")
    void preSend_subscribe_publicAuctionTopic_anonymousAllowed() {
        Message<byte[]> msg = stompMessage(
            StompCommand.SUBSCRIBE, null, "/topic/auction/42", null);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
    }

    @Test
    @DisplayName("SUBSCRIBE to authenticated topic from anonymous session is rejected")
    void preSend_subscribe_authedTopic_anonymousRejected() {
        Message<byte[]> msg = stompMessage(
            StompCommand.SUBSCRIBE, null, "/topic/ws-test", null);

        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
            .isInstanceOf(MessagingException.class)
            .hasMessageContaining("Authentication required");
    }

    @Test
    @DisplayName("SUBSCRIBE from an authenticated session is allowed regardless of destination")
    void preSend_subscribe_authedSession_allowed() {
        Principal principal = new StompAuthenticationToken(
            new AuthPrincipal(42L, "u@e.com", 1L, Role.USER));
        Message<byte[]> msg = stompMessage(
            StompCommand.SUBSCRIBE, null, "/topic/ws-test", principal);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
    }

    @Test
    @DisplayName("SUBSCRIBE to auction path with traversal segment is rejected for anonymous session")
    void subscribe_toAuctionWithPathTraversal_isRejected() {
        // A simple in-memory broker treats every literal string as a distinct
        // topic, so the traversal attempt has no subscribers today — but a
        // future swap to a STOMP relay (RabbitMQ, etc.) that normalizes paths
        // could let /topic/auction/../ws-test escape the allowlist and land
        // on /topic/ws-test. The strict regex rejects anything that isn't
        // /topic/auction/{positive-integer}.
        Message<byte[]> traversalFromRoot = stompMessage(
            StompCommand.SUBSCRIBE, null, "/topic/auction/../ws-test", null);
        assertThatThrownBy(() -> interceptor.preSend(traversalFromRoot, channel))
            .isInstanceOf(MessagingException.class)
            .hasMessageContaining("Authentication required");

        Message<byte[]> traversalAfterId = stompMessage(
            StompCommand.SUBSCRIBE, null, "/topic/auction/42/../ws-test", null);
        assertThatThrownBy(() -> interceptor.preSend(traversalAfterId, channel))
            .isInstanceOf(MessagingException.class)
            .hasMessageContaining("Authentication required");

        Message<byte[]> trailingSegment = stompMessage(
            StompCommand.SUBSCRIBE, null, "/topic/auction/42/extra", null);
        assertThatThrownBy(() -> interceptor.preSend(trailingSegment, channel))
            .isInstanceOf(MessagingException.class)
            .hasMessageContaining("Authentication required");

        Message<byte[]> nonNumericId = stompMessage(
            StompCommand.SUBSCRIBE, null, "/topic/auction/abc", null);
        assertThatThrownBy(() -> interceptor.preSend(nonNumericId, channel))
            .isInstanceOf(MessagingException.class)
            .hasMessageContaining("Authentication required");
    }

    @Test
    @DisplayName("SUBSCRIBE without destination is rejected for anonymous session")
    void preSend_subscribe_missingDestination_anonymousRejected() {
        Message<byte[]> msg = stompMessage(
            StompCommand.SUBSCRIBE, null, null, null);

        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
            .isInstanceOf(MessagingException.class)
            .hasMessageContaining("SUBSCRIBE missing destination");
    }

    // ------------------------------------------------------------------------
    // SEND-frame authorization
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("SEND from anonymous session is rejected")
    void preSend_send_anonymousRejected() {
        Message<byte[]> msg = stompMessage(
            StompCommand.SEND, null, "/app/bid", null);

        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
            .isInstanceOf(MessagingException.class)
            .hasMessageContaining("Authentication required to send");
    }

    @Test
    @DisplayName("SEND from authenticated session passes through")
    void preSend_send_authed_passesThrough() {
        Principal principal = new StompAuthenticationToken(
            new AuthPrincipal(42L, "u@e.com", 1L, Role.USER));
        Message<byte[]> msg = stompMessage(
            StompCommand.SEND, null, "/app/bid", principal);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
    }

    // ------------------------------------------------------------------------
    // Other frames
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("DISCONNECT frames pass through unchanged")
    void preSend_disconnect_passesThrough() {
        Message<byte[]> msg = stompMessage(StompCommand.DISCONNECT, null);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        Mockito.verifyNoInteractions(jwtService);
    }
}
