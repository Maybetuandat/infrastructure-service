// infrastructure-service/src/main/java/com/example/infrastructure_service/utils/PodLogWebSocketHandler.java
package com.example.infrastructure_service.utils;

import com.example.infrastructure_service.service.SshSessionCache;
import com.example.infrastructure_service.service.TerminalSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
@RequiredArgsConstructor
public class PodLogWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final SshSessionCache sshSessionCache;
    private final TerminalSessionService terminalSessionService;
    
    // ============= WEBSOCKET SESSIONS (Ephemeral - can disconnect/reconnect) =============
    private final Map<String, WebSocketSession> podSessions = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> connectionLatches = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> activeConnections = new ConcurrentHashMap<>();
    
    // ============= TERMINAL SESSIONS (Persistent - cleanup only on explicit action) =============
    private final Map<String, TerminalSessionData> terminalSessions = new ConcurrentHashMap<>();

    // ============= WEBSOCKET LIFECYCLE =============
    
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

        // Register WebSocket session (ephemeral)
        activeConnections.put(session.getId(), new AtomicBoolean(true));
        podSessions.put(podName, session);
        
        // Release connection latch if waiting
        CountDownLatch latch = connectionLatches.get(podName);
        if (latch != null) {
            latch.countDown();
            log.info("✅ WebSocket connection latch released for podName: {}", podName);
        }
        
        // Check if terminal session already exists (reconnect scenario)
        TerminalSessionData terminalSession = terminalSessions.get(podName);
        if (terminalSession != null && terminalSession.isActive()) {
            log.info("🔄 Reconnection detected - terminal session still active for: {}", podName);
            terminalSession.updateLastActivity();
            
            // Notify client that terminal is ready
            broadcastLogToPod(podName, "terminal_ready", 
                "Terminal reconnected. You can continue typing.", 
                Map.of("labSessionId", terminalSession.getLabSessionId()));
        } else {
            log.info("🆕 New connection - waiting for terminal setup for: {}", podName);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String podName = extractPodNameFromQuery(session.getUri().getQuery());
        
        log.info("🔌 WebSocket connection closed for session {} (podName: {}). Status: {}", 
            session.getId(), podName, status);
        
        // Remove WebSocket session (ephemeral)
        activeConnections.remove(session.getId());
        
        if (podName != null) {
            podSessions.remove(podName);
            
            // ✅ DO NOT cleanup terminal session here - it persists for reconnection
            // Terminal session will only be cleaned up by:
            // 1. Explicit cleanup call (e.g., from Kafka event when lab ends)
            // 2. Application shutdown (@PreDestroy)
            
            TerminalSessionData terminalSession = terminalSessions.get(podName);
            if (terminalSession != null && terminalSession.isActive()) {
                log.info("ℹ️ Terminal session still active for: {} (client can reconnect)", podName);
            }
            
            log.info("🗑️ Removed WebSocket session mapping for podName: {}", podName);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String podName = extractPodNameFromQuery(session.getUri().getQuery());
        
        log.error("❌ WebSocket transport error for session {} (podName: {}): {}", 
            session.getId(), podName, exception.getMessage());
        
        // Same as afterConnectionClosed - only cleanup WebSocket, not terminal
        activeConnections.remove(session.getId());
        
        if (podName != null) {
            podSessions.remove(podName);
            log.info("🗑️ Removed WebSocket session due to transport error for podName: {}", podName);
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
        
        // Check terminal session (persistent) not WebSocket session
        TerminalSessionData terminalSession = terminalSessions.get(podName);
        if (terminalSession != null && terminalSession.isActive()) {
            terminalSession.updateLastActivity();
            log.debug("📨 Terminal input from client for pod {}: {}", podName, message.getPayload());
            forwardToTerminal(terminalSession, message.getPayload());
        } else {
            log.warn("⚠️ No active terminal session for podName: {} - ignoring input", podName);
        }
    }

    // ============= TERMINAL SESSION LIFECYCLE MANAGEMENT =============
    
    /**
     * Setup terminal session when VM is ready
     * Called by VMUserSessionService after VM creation
     */
    public void setupTerminal(String podName, int labSessionId) {
        log.info("🔧 Setting up terminal session for podName: {} (labSessionId: {})", podName, labSessionId);
        
        try {
            String cacheKey = "lab-session-" + labSessionId;
            Session sshSession = sshSessionCache.get(cacheKey);
            
            if (sshSession == null || !sshSession.isConnected()) {
                log.error("❌ No cached SSH session found for labSessionId: {}", labSessionId);
                throw new IllegalStateException("SSH session not available");
            }

            log.info("✅ Found cached SSH session, opening shell channel...");
            
            // Open SSH shell channel
            ChannelShell channel = (ChannelShell) sshSession.openChannel("shell");
            channel.setPtyType("xterm");
            channel.setPtySize(80, 24, 640, 480);
            channel.connect();
            
            log.info("✅ SSH shell channel connected");

            InputStream in = channel.getInputStream();
            OutputStream out = channel.getOutputStream();

            // Start output reader thread
            Thread reader = new Thread(() -> readSshOutput(podName, in), 
                "ssh-reader-" + podName);
            reader.setDaemon(true);
            reader.start();
            
            log.info("✅ SSH output reader thread started");

            // Create terminal session data
            TerminalSessionData terminalSession = TerminalSessionData.builder()
                .podName(podName)
                .labSessionId(labSessionId)
                .active(true)
                .createdAt(LocalDateTime.now())
                .lastActivityAt(LocalDateTime.now())
                .sshChannel(channel)
                .sshOutputStream(out)
                .outputReaderThread(reader)
                .build();

            terminalSessions.put(podName, terminalSession);
            
            log.info("✅ Terminal session created and stored for: {}", podName);
            
            // Notify connected clients that terminal is ready
            broadcastLogToPod(podName, "terminal_ready", 
                "🎉 Terminal is ready! You can now type commands...", 
                Map.of("labSessionId", labSessionId, "percentage", 100));
                
        } catch (Exception e) {
            log.error("❌ Failed to setup terminal session for {}: {}", podName, e.getMessage(), e);
            
            // Notify clients of failure
            broadcastLogToPod(podName, "error", 
                "Failed to setup terminal: " + e.getMessage(), null);
        }
    }

    /**
     * Cleanup terminal session
     * Called when lab ends (via Kafka event, API call, or @PreDestroy)
     * 
     * TODO: This will be called by Kafka listener when lab session ends
     */
    public void cleanupTerminal(String podName) {
        log.info("🧹 Cleaning up terminal session for: {}", podName);
        
        TerminalSessionData terminalSession = terminalSessions.remove(podName);
        if (terminalSession == null) {
            log.warn("⚠️ No terminal session found for: {} (already cleaned up?)", podName);
            return;
        }

        // Mark as inactive
        terminalSession.setActive(false);
        
        // Stop output reader thread
        Thread reader = terminalSession.getOutputReaderThread();
        if (reader != null && reader.isAlive()) {
            reader.interrupt();
            log.debug("✅ Interrupted output reader thread for: {}", podName);
        }
        
        // Close SSH output stream
        OutputStream out = terminalSession.getSshOutputStream();
        if (out != null) {
            try {
                out.close();
                log.debug("✅ Closed SSH output stream for: {}", podName);
            } catch (IOException e) {
                log.debug("Error closing SSH output stream: {}", e.getMessage());
            }
        }
        
        // Disconnect SSH channel
        ChannelShell channel = terminalSession.getSshChannel();
        if (channel != null && channel.isConnected()) {
            channel.disconnect();
            log.debug("✅ Disconnected SSH channel for: {}", podName);
        }
        
        log.info("✅ Terminal session cleaned up successfully for: {}", podName);
        
        // Notify connected clients that terminal is closed
        broadcastLogToPod(podName, "terminal_closed", 
            "Lab session ended. Terminal is now closed.", 
            Map.of("reason", "lab_ended"));
    }

    /**
     * Cleanup all terminal sessions on application shutdown
     */
    @PreDestroy
    public void cleanupAllTerminals() {
        log.info("🧹 Application shutdown - cleaning up all terminal sessions...");
        
        int count = terminalSessions.size();
        terminalSessions.keySet().forEach(this::cleanupTerminal);
        
        log.info("✅ Cleaned up {} terminal sessions", count);
    }

    // ============= HELPER METHODS =============
    
    /**
     * Read SSH output and send to WebSocket clients
     */
    private void readSshOutput(String podName, InputStream in) {
        log.info("📖 Starting SSH output reader for: {}", podName);
        
        try {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                String output = new String(buffer, 0, bytesRead);
                sendTerminalOutput(podName, output);
            }
            log.info("📖 SSH output stream ended for: {}", podName);
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) {
                log.debug("SSH output reader interrupted for: {}", podName);
            } else {
                log.debug("SSH output reader stopped for {}: {}", podName, e.getMessage());
            }
        }
    }
    
    /**
     * Forward user input to SSH terminal
     */
    private void forwardToTerminal(TerminalSessionData terminalSession, String input) {
        OutputStream out = terminalSession.getSshOutputStream();
        
        if (out == null) {
            log.warn("⚠️ No SSH output stream for terminal session");
            return;
        }
        
        try {
            out.write(input.getBytes());
            out.flush();
            log.debug("📤 Forwarded input to SSH: {}", input);
        } catch (IOException e) {
            log.error("❌ Failed to forward input to SSH: {}", e.getMessage());
            
            // Mark session as inactive if SSH connection is broken
            terminalSession.setActive(false);
        }
    }

    /**
     * Wait for WebSocket client to connect
     * Used during VM creation to ensure client is ready before sending logs
     */
    public boolean waitForConnection(String podName, int timeoutSeconds) {
        if (hasActiveWebSocketSession(podName)) {
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

    /**
     * Check if there's an active WebSocket session for this pod
     */
    private boolean hasActiveWebSocketSession(String podName) {
        WebSocketSession session = podSessions.get(podName);
        if (session == null) {
            return false;
        }
        
        AtomicBoolean isActive = activeConnections.get(session.getId());
        return session.isOpen() && isActive != null && isActive.get();
    }

    /**
     * Broadcast log message to connected WebSocket clients (JSON format)
     */
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

    /**
     * Send raw terminal output to connected WebSocket clients
     */
    public void sendTerminalOutput(String podName, String output) {
        WebSocketSession session = podSessions.get(podName);
        
        if (session == null || !session.isOpen()) {
            log.debug("⚠️ No active WebSocket session for podName: {}", podName);
            return;
        }

        try {
            session.sendMessage(new TextMessage(output));
            log.debug("📤 Sent terminal output to {} ({} bytes)", podName, output.length());
        } catch (IOException e) {
            log.error("❌ Failed to send terminal output to {}: {}", podName, e.getMessage());
        }
    }

    /**
     * Extract podName from WebSocket query string
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

    // ============= INNER CLASS: TERMINAL SESSION DATA =============
    
    /**
     * Represents a persistent terminal session
     * This persists across WebSocket reconnections
     */
    @lombok.Data
    @lombok.Builder
    private static class TerminalSessionData {
        private String podName;
        private int labSessionId;
        private boolean active;
        private LocalDateTime createdAt;
        private LocalDateTime lastActivityAt;
        
        // SSH resources
        private ChannelShell sshChannel;
        private OutputStream sshOutputStream;
        private Thread outputReaderThread;
        
        public void updateLastActivity() {
            this.lastActivityAt = LocalDateTime.now();
        }
    }
}