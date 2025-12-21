package com.example.infrastructure_service.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
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
public class PodLogWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, WebSocketSession> podSessions = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> connectionLatches = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> activeConnections = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String podName = extractPodNameFromQuery(session.getUri().getQuery());
        
        if (podName == null || podName.isEmpty()) {
            log.error("❌ No podName provided in query string");
            session.close();
            return;
        }

        log.info("📡 WebSocket connection established for session {} with podName {}", 
            session.getId(), podName);

        activeConnections.put(session.getId(), new AtomicBoolean(true));
        podSessions.put(podName, session);
        
        CountDownLatch latch = connectionLatches.get(podName);
        if (latch != null) {
            latch.countDown();
            log.info("✅ WebSocket connection latch released for podName: {}", podName);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String podName = extractPodNameFromQuery(session.getUri().getQuery());
        
        log.info("🔌 WebSocket connection closed for session {} (podName: {}). Status: {}", 
            session.getId(), podName, status);
        
        activeConnections.remove(session.getId());
        if (podName != null) {
            podSessions.remove(podName);
            log.info("🗑️ Removed session for podName: {}", podName);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String podName = extractPodNameFromQuery(session.getUri().getQuery());
        
        log.error("❌ WebSocket transport error for session {} (podName: {}): {}", 
            session.getId(), podName, exception.getMessage());
        
        activeConnections.remove(session.getId());
        if (podName != null) {
            podSessions.remove(podName);
        }
        
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.SERVER_ERROR);
            }
        } catch (Exception e) {
            log.debug("Error closing session after transport error: {}", e.getMessage());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String podName = extractPodNameFromQuery(session.getUri().getQuery());
        
        if (podName == null) {
            log.debug("No podName for session {}", session.getId());
            return;
        }
        
        // Forward terminal input to SSH session
        // This will be handled by TerminalSessionService
        log.debug("Received terminal input from client for pod {}: {}", podName, message.getPayload());
        
        // TODO: Forward to SSH session based on podName
    }

    public boolean waitForConnection(String podName, int timeoutSeconds) {
        if (hasActiveSessionsForPod(podName)) {
            log.info("✅ WebSocket already connected for pod: {}", podName);
            return true;
        }
        
        log.info("⏳ Waiting for WebSocket connection for pod: {} (timeout: {}s)", podName, timeoutSeconds);
        
        CountDownLatch latch = new CountDownLatch(1);
        connectionLatches.put(podName, latch);
        
        try {
            boolean connected = latch.await(timeoutSeconds, TimeUnit.SECONDS);
            
            if (connected) {
                log.info("✅ WebSocket client connected for podName: {} within {}s", podName, timeoutSeconds);
                return true;
            } else {
                log.warn("⏰ WebSocket connection timeout for podName: {} after {}s", podName, timeoutSeconds);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ Interrupted while waiting for WebSocket connection for podName: {}", podName);
            return false;
        } finally {
            connectionLatches.remove(podName);
        }
    }

    private boolean hasActiveSessionsForPod(String podName) {
        WebSocketSession session = podSessions.get(podName);
        if (session == null) {
            return false;
        }
        
        AtomicBoolean isActive = activeConnections.get(session.getId());
        return session.isOpen() && isActive != null && isActive.get();
    }

    public void broadcastLogToPod(String podName, String type, String message, Map<String, Object> data) {
        WebSocketSession session = podSessions.get(podName);
        
        if (session == null) {
            log.debug("⚠️ No WebSocket session found for podName: {}", podName);
            return;
        }

        String sessionId = session.getId();
        AtomicBoolean isActive = activeConnections.get(sessionId);
        
        if (isActive == null || !isActive.get()) {
            log.debug("⚠️ WebSocket session {} is inactive for podName: {}", sessionId, podName);
            return;
        }

        if (!session.isOpen()) {
            log.debug("⚠️ WebSocket session {} is closed for podName: {}", sessionId, podName);
            podSessions.remove(podName);
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
            
            log.debug("📤 Sent WebSocket message to {} (type: {})", podName, type);
        } catch (IOException e) {
            log.error("❌ Failed to send WebSocket message to podName {}: {}", podName, e.getMessage());
            podSessions.remove(podName);
            activeConnections.remove(sessionId);
        }
    }

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