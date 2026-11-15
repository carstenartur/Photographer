package com.photographer.focusstack.camera.config;

import com.photographer.focusstack.app.api.RealtimeApiPaths;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

@Configuration
@EnableWebSocketMessageBroker
@EnableWebSocket
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer, WebSocketConfigurer {

    private final PlainWebSocketBridge plainWebSocketBridge;

    public WebSocketConfig(PlainWebSocketBridge plainWebSocketBridge) {
        this.plainWebSocketBridge = plainWebSocketBridge;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker(RealtimeApiPaths.BROKER_PREFIX);
        config.setApplicationDestinationPrefixes(RealtimeApiPaths.APPLICATION_PREFIX);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // SockJS also registers /ws/**. Let the exact /ws/plain mapping run first.
        registry.setOrder(2);
        registry.addEndpoint(RealtimeApiPaths.STOMP_ENDPOINT)
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    /**
     * Registers the plain (non-STOMP) WebSocket endpoint at {@code /ws/plain}
     * so the browser can connect with a simple {@code new WebSocket(url)}.
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        var attributes = new HttpSessionHandshakeInterceptor(List.of());
        attributes.setCreateSession(false);
        registry.addHandler(plainWebSocketBridge, RealtimeApiPaths.PLAIN_ENDPOINT)
                .addInterceptors(attributes)
                .setAllowedOriginPatterns("*");
    }
}
