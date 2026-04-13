package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.auth.exception.TokenExpiredException;
import com.slparcelauctions.backend.auth.exception.TokenInvalidException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * STOMP CONNECT-frame authentication gate. Reads the {@code Authorization}
 * native header from the first {@link StompCommand#CONNECT} frame, parses it
 * via {@link JwtService}, and attaches a {@link StompAuthenticationToken} to
 * the session. Subsequent frames (SUBSCRIBE, SEND, DISCONNECT) pass through
 * unchanged — the principal set here lives on the session for their lifetime.
 *
 * <p><strong>Why CONNECT-only:</strong> Task 01-09 spec §3 (Q3c-i). Matching
 * the HTTP filter's one-check-per-request behavior. Epic 04 may revisit if
 * sensitive write operations ever flow through {@code @MessageMapping}
 * handlers.
 *
 * <p><strong>Why throw {@link MessagingException}:</strong> Spring's STOMP
 * infrastructure catches it and sends a STOMP ERROR frame back to the client
 * before closing the session. The frontend's {@code onStompError} handler
 * picks it up and surfaces the error via the ConnectionState machine.
 *
 * <p><strong>Why {@code getFirstNativeHeader}, not {@code getHeader}:</strong>
 * STOMP headers arrive as a {@code MultiValueMap<String, String>} under the
 * key {@code nativeHeaders}. {@code getHeader} looks up Spring-internal
 * framework headers and returns null for STOMP client headers.
 * See FOOTGUNS §F.16.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtChannelInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
            MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("STOMP CONNECT rejected: missing or non-Bearer Authorization header");
            throw new MessagingException(message, "Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7);
        try {
            AuthPrincipal principal = jwtService.parseAccessToken(token);
            accessor.setUser(new StompAuthenticationToken(principal));
            log.debug("STOMP CONNECT authenticated: userId={}", principal.userId());
            return message;
        } catch (TokenExpiredException | TokenInvalidException e) {
            log.debug("STOMP CONNECT rejected: {}", e.getMessage());
            throw new MessagingException(message, "Invalid or expired access token");
        }
    }
}
