package com.photographer.focusstack.camera.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plain (non-STOMP) WebSocket handler that broadcasts job events to all
 * connected browser clients.
 *
 * <p>The frontend opens a plain {@code new WebSocket("ws://…/ws/plain")} and
 * receives JSON messages for every job event (progress, logs, preview).
 * This sidesteps the STOMP/SockJS framing that the STOMP broker uses on
 * {@code /ws}, which a plain browser WebSocket cannot decode.
 *
 * <p>{@link com.photographer.focusstack.camera.service.StackingJobService}
 * calls {@link #broadcast(Map)} whenever it sends a job event, so both STOMP
 * subscribers <em>and</em> plain-WebSocket clients receive the messages.
 */
@Component
public class PlainWebSocketBridge extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(PlainWebSocketBridge.class);

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper;

    public PlainWebSocketBridge(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        sessions.add(session);
        log.debug("Plain WS client connected: {} (total={})", session.getId(), sessions.size());
    }

    /** Stop streams already using a browser grant when the browser signs out or changes identity. */
    public void closeBrowserSession(String id) {
        for (WebSocketSession socket : sessions) {
            if (id.equals(socket.getAttributes().get(HttpSessionHandshakeInterceptor.HTTP_SESSION_ID_ATTR_NAME))) {
                sessions.remove(socket);
                try {
                    socket.close(CloseStatus.NORMAL);
                } catch (IOException ignored) {
                    // A concurrent disconnect already closed the transport.
                }
            }
        }
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        sessions.remove(session);
        log.debug("Plain WS client disconnected: {} (total={})", session.getId(), sessions.size());
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) {
        sessions.remove(session);
        log.debug("Plain WS transport error for {}: {}", session.getId(), exception.getMessage());
    }

    /**
     * Broadcasts a JSON message to all connected plain-WebSocket clients.
     *
     * @param payload a map that will be serialised as JSON; should include
     *                at least {@code jobId} so the client can filter by job
     */
    public void broadcast(Map<String, Object> payload) {
        if (sessions.isEmpty()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(payload);
            TextMessage message = new TextMessage(json);
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    try {
                        synchronized (session) {
                            session.sendMessage(message);
                        }
                    } catch (IOException e) {
                        log.debug("Failed to send to WS session {}: {}", session.getId(), e.getMessage());
                        sessions.remove(session);
                    }
                } else {
                    sessions.remove(session);
                }
            }
        } catch (JacksonException e) {
            log.warn("Failed to serialise WS broadcast payload: {}", e.getMessage());
        }
    }
}
