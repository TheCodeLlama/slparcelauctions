package com.slparcelauctions.backend.auth;

import com.slparcelauctions.backend.auth.exception.TokenExpiredException;
import com.slparcelauctions.backend.auth.exception.TokenInvalidException;
import java.util.regex.Pattern;

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
 * STOMP CONNECT-frame authentication gate + per-SUBSCRIBE destination
 * authorization. Reads the {@code Authorization} native header from the
 * first {@link StompCommand#CONNECT} frame, parses it via {@link JwtService}
 * when present, and attaches a {@link StompAuthenticationToken} to the
 * session. Subsequent frames pass through unchanged — the principal set here
 * lives on the session for its lifetime.
 *
 * <p><strong>Anonymous CONNECT:</strong> Epic 04 sub-spec 1 §4 makes
 * {@code /topic/auction/**} publicly subscribable. Clients that only need
 * the public auction feed can CONNECT without an {@code Authorization}
 * header. Malformed or expired tokens are still rejected — only *absence*
 * of the header opts the session into anonymous mode.
 *
 * <p><strong>SUBSCRIBE gate:</strong> to prevent an anonymous session from
 * subscribing to authenticated topics (e.g. {@code /topic/ws-test} in
 * dev/test, and any future per-user queues), SUBSCRIBE frames are checked
 * against a strict regex allowlist of public destinations
 * ({@link #PUBLIC_AUCTION_DESTINATION}). Everything outside the allowlist
 * requires a principal on the session. See FOOTGUNS §F.16.1 for why this is
 * a regex rather than a prefix check.
 *
 * <p><strong>Why CONNECT-only for JWT validation:</strong> Task 01-09 spec
 * §3 (Q3c-i). Matching the HTTP filter's one-check-per-request behavior.
 * Epic 04 may revisit if sensitive write operations ever flow through
 * {@code @MessageMapping} handlers.
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

    /**
     * Exact-shape allowlist for destinations a non-authenticated session may
     * SUBSCRIBE to. Matches {@code /topic/auction/{id}} where {@code id} is a
     * positive integer (the auction primary key).
     *
     * <p>Using a strict regex rather than a {@code startsWith} prefix check
     * guards against path-traversal-style escapes if the broker is ever
     * swapped from Spring's simple in-memory broker (which treats destinations
     * as opaque strings) to a relay like RabbitMQ that normalizes paths. A
     * crafted destination such as {@code /topic/auction/../ws-test} would pass
     * a prefix check but could route to {@code /topic/ws-test} on a
     * normalizing broker. The regex forces {@code id} to be digits only, so
     * any traversal or extension segment fails the match.
     */
    private static final Pattern PUBLIC_AUCTION_DESTINATION =
        Pattern.compile("^/topic/auction/\\d+$");

    private final JwtService jwtService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
            MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        if (command == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(command)) {
            handleConnect(message, accessor);
            return message;
        }

        if (StompCommand.SUBSCRIBE.equals(command)) {
            handleSubscribe(message, accessor);
            return message;
        }

        if (StompCommand.SEND.equals(command)) {
            // All current SEND destinations require authentication. If future
            // features introduce public SEND destinations, mirror the
            // handleSubscribe regex allowlist here.
            if (accessor.getUser() == null) {
                log.debug("STOMP SEND rejected: anonymous session cannot send");
                throw new MessagingException(message, "Authentication required to send");
            }
        }

        return message;
    }

    private void handleConnect(Message<?> message, StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null) {
            // Anonymous connect — session will still be gated at SUBSCRIBE
            // time against the public destination allowlist.
            log.debug("STOMP CONNECT accepted as anonymous (no Authorization header)");
            return;
        }
        if (!authHeader.startsWith("Bearer ")) {
            log.debug("STOMP CONNECT rejected: non-Bearer Authorization header");
            throw new MessagingException(message, "Invalid Authorization header");
        }

        String token = authHeader.substring(7);
        try {
            AuthPrincipal principal = jwtService.parseAccessToken(token);
            accessor.setUser(new StompAuthenticationToken(principal));
            log.debug("STOMP CONNECT authenticated: userId={}", principal.userId());
        } catch (TokenExpiredException | TokenInvalidException e) {
            log.debug("STOMP CONNECT rejected: {}", e.getMessage());
            throw new MessagingException(message, "Invalid or expired access token");
        }
    }

    private void handleSubscribe(Message<?> message, StompHeaderAccessor accessor) {
        if (accessor.getUser() != null) {
            // Authenticated sessions may subscribe to anything — spec §4
            // makes public topics a superset of what auth'd users can see.
            return;
        }

        String destination = accessor.getDestination();
        if (destination == null) {
            log.debug("STOMP SUBSCRIBE rejected: no destination");
            throw new MessagingException(message, "SUBSCRIBE missing destination");
        }

        if (PUBLIC_AUCTION_DESTINATION.matcher(destination).matches()) {
            log.debug("STOMP SUBSCRIBE accepted as anonymous: {}", destination);
            return;
        }

        log.debug("STOMP SUBSCRIBE rejected: anonymous session cannot subscribe to {}", destination);
        throw new MessagingException(message, "Authentication required for destination " + destination);
    }
}
