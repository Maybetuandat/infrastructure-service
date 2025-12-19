package com.example.infrastructure_service.utils;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.example.infrastructure_service.dto.WebSocketMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class PodLogWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    
    // Lưu trữ các session theo sessionId
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    
    // Lưu trữ mapping giữa sessionId và podName để filter messages
    private final ConcurrentHashMap<String, String> sessionPodMapping = new ConcurrentHashMap<>();
    
    // Lưu trữ các CountDownLatch đang đợi WebSocket connection
    private final ConcurrentHashMap<String, CountDownLatch> connectionLatches = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        sessions.put(sessionId, session);
        
        // Lấy podName từ query parameters
        String query = session.getUri().getQuery();
        String podName = extractPodNameFromQuery(query);
        
        if (podName != null) {
            sessionPodMapping.put(sessionId, podName);
            log.info(" WebSocket connection established for session {} with podName {}", sessionId, podName);
            
            // Release latch để cho phép Kafka consumer tiếp tục xử lý
            CountDownLatch latch = connectionLatches.remove(podName);
            if (latch != null) {
                log.info("Releasing connection latch for podName: {}", podName);
                latch.countDown();
            }
            
            // Gửi message confirmation
            sendMessage(session, new WebSocketMessage("connection", 
                "Connected to pod logs stream for: " + podName, null));
        } else {
            log.warn(" WebSocket connection established but no podName found in query");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        String podName = sessionPodMapping.remove(sessionId);
        sessions.remove(sessionId);
        
        log.info(" WebSocket connection closed for session {} (podName: {}). Status: {}", 
            sessionId, podName, status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        log.debug("Received message from session {}: {}", session.getId(), message.getPayload());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket transport error for session {}: {}", session.getId(), exception.getMessage());
    }

    public boolean waitForConnection(String podName, int timeoutSeconds) {
        log.info(" Waiting for WebSocket connection for podName: {} (timeout: {}s)", podName, timeoutSeconds);
        
        // Kiểm tra xem đã có connection chưa
        if (isConnected(podName)) {
            log.info(" WebSocket already connected for podName: {}", podName);
            return true;
        }
        
        // Tạo latch mới
        CountDownLatch latch = new CountDownLatch(1);
        connectionLatches.put(podName, latch);
        
        try {
            boolean connected = latch.await(timeoutSeconds, TimeUnit.SECONDS);
            
            if (connected) {
                log.info(" WebSocket connection successful for podName: {}", podName);
            } else {
                log.warn(" WebSocket connection timeout for podName: {} after {}s", podName, timeoutSeconds);
                connectionLatches.remove(podName);
            }
            
            return connected;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error(" Interrupted while waiting for WebSocket connection: {}", e.getMessage());
            connectionLatches.remove(podName);
            return false;
        }
    }

    private boolean isConnected(String podName) {
        return sessionPodMapping.containsValue(podName) && 
               sessions.values().stream()
                   .anyMatch(s -> podName.equals(sessionPodMapping.get(s.getId())) && s.isOpen());
    }

    /**
     * Broadcast log message đến tất cả sessions đang theo dõi pod này
     * Synchronized để tránh concurrent sending
     */
    public void broadcastLogToPod(String podName, String type, String message, Object metadata) {
        WebSocketMessage wsMessage = new  WebSocketMessage(type, message, metadata);
        
        sessionPodMapping.entrySet().stream()
            .filter(entry -> podName.equals(entry.getValue()))
            .forEach(entry -> {
                String sessionId = entry.getKey();
                WebSocketSession session = sessions.get(sessionId);
                
                if (session != null && session.isOpen()) {
                    sendMessageSafely(session, wsMessage);
                }
            });
    }

    /**
     * Gửi message đến một session cụ thể với synchronization
     */
    private synchronized void sendMessageSafely(WebSocketSession session, WebSocketMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (IOException e) {
            log.error("Failed to send WebSocket message to session {}: {}", 
                session.getId(), e.getMessage());
        }
    }

    /**
     * Gửi message đến một session cụ thể
     */
    private void sendMessage(WebSocketSession session, WebSocketMessage message) {
        sendMessageSafely(session, message);
    }

    /**
     * Extract podName từ query string
     */
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

   
}