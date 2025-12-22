// infrastructure-service/src/main/java/com/example/infrastructure_service/handler/AdminTestWebSocketHandler.java
package com.example.infrastructure_service.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
@RequiredArgsConstructor
public class AdminTestWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    
    // Map: podName -> WebSocketSession
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    
    // Map: sessionId -> isActive flag
    private final Map<String, AtomicBoolean> activeConnections = new ConcurrentHashMap<>();
    
    // Map: podName -> connection latch (for waitForConnection)
    private final Map<String, CountDownLatch> connectionLatches = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String podName = extractPodNameFromQuery(session.getUri().getQuery());
        
        if (podName == null || podName.isEmpty()) {
            log.error("❌ No podName provided in query string for admin test");
            session.close();
            return;
        }

        log.info("📡 Admin test WebSocket connected: session={}, podName={}", 
            session.getId(), podName);

        // Register session
        activeConnections.put(session.getId(), new AtomicBoolean(true));
        sessions.put(podName, session);
        
        // Release connection latch if waiting
        CountDownLatch latch = connectionLatches.get(podName);
        if (latch != null) {
            latch.countDown();
            log.info("✅ WebSocket connection latch released for podName: {}", podName);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String podName = extractPodNameFromQuery(session.getUri().getQuery());
        
        log.info("🔌 Admin test WebSocket disconnected: session={}, podName={}, status={}", 
            session.getId(), podName, status);
        
        activeConnections.remove(session.getId());
        
        if (podName != null) {
            sessions.remove(podName);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String podName = extractPodNameFromQuery(session.getUri().getQuery());
        
        log.error("❌ Admin test WebSocket error: session={}, podName={}, error={}", 
            session.getId(), podName, exception.getMessage());
        
        activeConnections.remove(session.getId());
        
        if (podName != null) {
            sessions.remove(podName);
        }
        
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.SERVER_ERROR);
            }
        } catch (Exception e) {
            log.debug("Error closing session: {}", e.getMessage());
        }
    }

    /**
     * Wait for WebSocket client to connect
     */
    public boolean waitForConnection(String podName, int timeoutSeconds) {
        if (hasActiveSession(podName)) {
            log.info("✅ Admin test WebSocket already connected for: {}", podName);
            return true;
        }
        
        log.info("⏳ Waiting for admin test WebSocket connection: {} (timeout: {}s)", 
            podName, timeoutSeconds);
        
        CountDownLatch latch = new CountDownLatch(1);
        connectionLatches.put(podName, latch);
        
        try {
            boolean connected = latch.await(timeoutSeconds, TimeUnit.SECONDS);
            
            if (connected) {
                log.info("✅ Admin test WebSocket connected within {}s: {}", timeoutSeconds, podName);
                return true;
            } else {
                log.warn("⏰ Admin test WebSocket timeout after {}s: {}", timeoutSeconds, podName);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ Interrupted while waiting for connection: {}", podName);
            return false;
        } finally {
            connectionLatches.remove(podName);
        }
    }

    /**
     * Check if there's an active session
     */
    private boolean hasActiveSession(String podName) {
        WebSocketSession session = sessions.get(podName);
        if (session == null) {
            return false;
        }
        
        AtomicBoolean isActive = activeConnections.get(session.getId());
        return session.isOpen() && isActive != null && isActive.get();
    }

    /**
     * Broadcast log message to connected client
     */
    public void broadcastLog(String podName, String type, String message, Map<String, Object> data) {
        WebSocketSession session = sessions.get(podName);
        
        if (session == null) {
            log.debug("⚠️ No admin test session for: {}", podName);
            return;
        }

        String sessionId = session.getId();
        AtomicBoolean isActive = activeConnections.get(sessionId);
        
        if (isActive == null || !isActive.get()) {
            log.debug("⚠️ Inactive admin test session: {}", podName);
            return;
        }

        if (!session.isOpen()) {
            log.debug("⚠️ Closed admin test session: {}", podName);
            sessions.remove(podName);
            activeConnections.remove(sessionId);
            return;
        }

        try {
            Map<String, Object> payload = Map.of(
                "type", type,
                "message", message,
                "data", data != null ? data : Map.of(),
                "timestamp", System.currentTimeMillis()
            );
            
            String json = objectMapper.writeValueAsString(payload);
            session.sendMessage(new TextMessage(json));
            
            log.debug("📤 Admin test log sent: podName={}, type={}", podName, type);
        } catch (IOException e) {
            log.error("❌ Failed to send admin test log to {}: {}", podName, e.getMessage());
            sessions.remove(podName);
            activeConnections.remove(sessionId);
        }
    }

    /**
     * Extract podName from query string
     */
    private String extractPodNameFromQuery(String query) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        
        for (String param : query.split("&")) {
            String[] keyValue = param.split("=");
            if (keyValue.length == 2 && "podName".equals(keyValue[0])) {
                return keyValue[1];
            }
        }
        return null;
    }
}