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

        String sessionId = session.getId();
        AtomicBoolean isActive = activeConnections.remove(sessionId);
        if (isActive != null) {
            isActive.set(false);
        }

        if (podName != null) {
            WebSocketSession existingSession = podSessions.get(podName);
            if (existingSession != null && existingSession.getId().equals(session.getId())) {
                podSessions.remove(podName);
                log.info("🗑️ Removed session for podName: {}", podName);
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String podName = extractPodNameFromQuery(session.getUri().getQuery());
        
        if (exception.getMessage() != null && 
            (exception.getMessage().contains("Connection reset") ||
             exception.getMessage().contains("Broken pipe") ||
             exception.getMessage().contains("Session closed"))) {
            log.debug("🔌 Transport error (normal close) for session {}: {}", 
                session.getId(), exception.getMessage());
        } else {
            log.error("🚨 Transport error for session {} (podName: {}): {}", 
                session.getId(), podName, exception.getMessage());
        }

        String sessionId = session.getId();
        AtomicBoolean isActive = activeConnections.remove(sessionId);
        if (isActive != null) {
            isActive.set(false);
        }

        if (podName != null) {
            podSessions.remove(podName);
        }
    }

    public boolean waitForConnection(String podName, int timeoutSeconds) {
        CountDownLatch latch = new CountDownLatch(1);
        connectionLatches.put(podName, latch);
        
        try {
            boolean connected = latch.await(timeoutSeconds, TimeUnit.SECONDS);
            if (connected) {
                log.info("✅ WebSocket client connected for podName: {} within {}s", podName, timeoutSeconds);
            } else {
                log.warn("⏰ WebSocket connection timeout for podName: {} after {}s", podName, timeoutSeconds);
            }
            return connected;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ Interrupted while waiting for WebSocket connection for podName: {}", podName);
            return false;
        } finally {
            connectionLatches.remove(podName);
        }
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
            activeConnections.remove(sessionId);
            podSessions.remove(podName);
            return;
        }

        WebSocketMessage wsMessage = new WebSocketMessage(type, message, data);
        sendMessageSafely(session, wsMessage);
    }

    private synchronized void sendMessageSafely(WebSocketSession session, WebSocketMessage message) {
        try {
            String sessionId = session.getId();
            AtomicBoolean isActive = activeConnections.get(sessionId);
            
            if (isActive == null || !isActive.get() || !session.isOpen()) {
                log.debug("⚠️ Skipping message send - session {} is closed or inactive", sessionId);
                return;
            }

            String json = objectMapper.writeValueAsString(message);
            
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (IOException e) {
            if (e.getMessage() != null && 
                (e.getMessage().contains("Session closed") ||
                 e.getMessage().contains("Connection reset") ||
                 e.getMessage().contains("Broken pipe"))) {
                log.debug("Session closed during message send: {}", e.getMessage());
            } else {
                log.error("Failed to send WebSocket message to session {}: {}", 
                    session.getId(), e.getMessage());
            }
            
            String sessionId = session.getId();
            AtomicBoolean isActive = activeConnections.remove(sessionId);
            if (isActive != null) {
                isActive.set(false);
            }
        }
    }

    private String extractPodNameFromQuery(String query) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        
        String[] params = query.split("&");
        for (String param : params) {
            String[] keyValue = param.split("=");
            if (keyValue.length == 2 && "podName".equals(keyValue[0])) {
                return keyValue[1];
            }
        }
        
        return null;
    }

    public static class WebSocketMessage {
        private String type;
        private String message;
        private Map<String, Object> data;

        public WebSocketMessage() {}

        public WebSocketMessage(String type, String message, Map<String, Object> data) {
            this.type = type;
            this.message = message;
            this.data = data;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        
        public Map<String, Object> getData() { return data; }
        public void setData(Map<String, Object> data) { this.data = data; }
    }
}