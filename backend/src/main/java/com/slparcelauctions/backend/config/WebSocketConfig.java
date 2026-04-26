package com.slparcelauctions.backend.config;

import com.slparcelauctions.backend.auth.JwtChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket configuration. Exposes a single endpoint at {@code /ws}
 * with SockJS fallback, a simple in-memory broker for {@code /topic}
 * destinations, and the {@link JwtChannelInterceptor} on the client inbound
 * channel.
 *
 * <p><strong>In-memory broker:</strong> sufficient for Phase 1 single-instance
 * deployments. Epic 04 may swap to a relayed broker (RabbitMQ STOMP plugin)
 * when cross-instance fanout is needed. Leaving this comment so the Epic 04
 * implementer does not need to rediscover the rationale.
 *
 * <p><strong>{@code /app} application prefix:</strong> unused in Task 01-09
 * (the test controller uses REST + {@code SimpMessagingTemplate}) but set
 * here so Epic 04 can add {@code @MessageMapping("/bid")} handlers without
 * reconfiguring the broker.
 *
 * <p><strong>CORS on the endpoint:</strong> Spring's STOMP endpoint CORS is
 * configured separately from the main {@code HttpSecurity} CORS source.
 * Without {@code .setAllowedOrigins()} the SockJS handshake XHR fails with a
 * confusing CORS error even though the main security filter passes.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtChannelInterceptor jwtChannelInterceptor;

    @Value("${cors.allowed-origin:http://localhost:3000}")
    private String allowedOrigin;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setUserDestinationPrefix("/user");  // Explicit, even though /user is the default.
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            .setAllowedOrigins(allowedOrigin)
            .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(jwtChannelInterceptor);
    }
}
