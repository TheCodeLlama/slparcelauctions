package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.auth.exception.TokenExpiredException;
import com.slparcelauctions.backend.auth.exception.TokenInvalidException;
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
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (authHeader != null) {
            accessor.setNativeHeader("Authorization", authHeader);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("non-CONNECT frames pass through unchanged")
    void preSend_nonConnectFrame_passesThrough() {
        Message<byte[]> msg = stompMessage(StompCommand.SEND, null);

        Message<?> result = interceptor.preSend(msg, channel);

        assertThat(result).isSameAs(msg);
        Mockito.verifyNoInteractions(jwtService);
    }

    @Test
    @DisplayName("CONNECT with missing Authorization header is rejected")
    void preSend_connectFrame_missingAuthHeader_throws() {
        Message<byte[]> msg = stompMessage(StompCommand.CONNECT, null);

        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
            .isInstanceOf(MessagingException.class)
            .hasMessageContaining("Missing or invalid Authorization header");

        Mockito.verifyNoInteractions(jwtService);
    }

    @Test
    @DisplayName("CONNECT with malformed Bearer header is rejected")
    void preSend_connectFrame_malformedBearer_throws() {
        Message<byte[]> msg = stompMessage(StompCommand.CONNECT, "NotBearer foo");

        assertThatThrownBy(() -> interceptor.preSend(msg, channel))
            .isInstanceOf(MessagingException.class)
            .hasMessageContaining("Missing or invalid Authorization header");

        Mockito.verifyNoInteractions(jwtService);
    }

    @Test
    @DisplayName("CONNECT with valid token attaches StompAuthenticationToken principal")
    void preSend_connectFrame_validToken_attachesPrincipal() {
        AuthPrincipal authPrincipal = new AuthPrincipal(42L, "test@example.com", 1L);
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
}
