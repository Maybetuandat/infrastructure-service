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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    
    private final Map<String, WebSocketSession> podSessions = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> connectionLatches = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> activeConnections = new ConcurrentHashMap<>();
    private final Map<String, Boolean> terminalModeEnabled = new ConcurrentHashMap<>();
    
    // Terminal SSH resources
    private final Map<String, ChannelShell> sshChannels = new ConcurrentHashMap<>();
    private final Map<String, Thread> outputReaders = new ConcurrentHashMap<>();
    private final Map<String, OutputStream> sshOutputStreams = new ConcurrentHashMap<>();

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
        
        // Check if terminal mode is already enabled for this pod
        Boolean isTerminalMode = terminalModeEnabled.get(podName);
        if (isTerminalMode == null) {
            terminalModeEnabled.put(podName, false);
        }
        
        CountDownLatch latch = connectionLatches.get(podName);
        if (latch != null) {
            latch.countDown();
            log.info("✅ WebSocket connection latch released for podName: {}", podName);
        }
        
        // ✅ Send terminal_ready if terminal is already set up
        if (Boolean.TRUE.equals(isTerminalMode)) {
            log.info("🎉 Sending terminal_ready message for reconnected client: {}", podName);
            broadcastLogToPod(podName, "terminal_ready", 
                "Terminal is ready. You can now type commands.", null);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String podName = extractPodNameFromQuery(session.getUri().getQuery());
        
        log.info("🔌 WebSocket connection closed for session {} (podName: {}). Status: {}", 
            session.getId(), podName, status);
        
        activeConnections.remove(session.getId());
        
        if (podName != null) {
            cleanupTerminalResources(podName);
            podSessions.remove(podName);
            terminalModeEnabled.remove(podName);
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
            cleanupTerminalResources(podName);
            podSessions.remove(podName);
            terminalModeEnabled.remove(podName);
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
        
        Boolean isTerminalMode = terminalModeEnabled.get(podName);
        if (Boolean.TRUE.equals(isTerminalMode)) {
            log.debug("📨 Terminal input from client for pod {}: {}", podName, message.getPayload());
            forwardToTerminal(podName, message.getPayload());
        }
    }

    public void enableTerminalMode(String podName, int labSessionId) {
        terminalModeEnabled.put(podName, true);
        log.info("🔄 Terminal mode ENABLED for podName: {}", podName);
        
        // Open SSH channel for terminal I/O
        try {
            setupTerminalChannel(podName, labSessionId);
        } catch (Exception e) {
            log.error("❌ Failed to setup terminal channel for {}: {}", podName, e.getMessage());
        }
    }

    private void setupTerminalChannel(String podName, int labSessionId) throws Exception {
        String cacheKey = "lab-session-" + labSessionId;
        Session sshSession = sshSessionCache.get(cacheKey);
        
        if (sshSession == null || !sshSession.isConnected()) {
            log.error("❌ No cached SSH session found for labSessionId: {}", labSessionId);
            return;
        }

        log.info("🔧 Setting up terminal channel for podName: {}", podName);
        
        ChannelShell channel = (ChannelShell) sshSession.openChannel("shell");
        channel.setPtyType("xterm");
        channel.setPtySize(80, 24, 640, 480);
        channel.connect();

        sshChannels.put(podName, channel);

        InputStream in = channel.getInputStream();
        OutputStream out = channel.getOutputStream();
        sshOutputStreams.put(podName, out);

        // Start reading SSH output → send to WebSocket
        Thread reader = new Thread(() -> {
            try {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    String output = new String(buffer, 0, bytesRead);
                    sendTerminalOutput(podName, output);
                }
            } catch (IOException e) {
                log.debug("Terminal output reader stopped for {}: {}", podName, e.getMessage());
            }
        });
        reader.start();
        outputReaders.put(podName, reader);

        log.info("✅ Terminal channel ready for podName: {}", podName);
    }

    private void forwardToTerminal(String podName, String input) {
        OutputStream out = sshOutputStreams.get(podName);
        
        if (out == null) {
            log.warn("⚠️ No SSH output stream for podName: {}", podName);
            return;
        }
        
        try {
            out.write(input.getBytes());
            out.flush();
            log.debug("📤 Forwarded input to SSH: {}", input);
        } catch (IOException e) {
            log.error("❌ Failed to forward input to SSH: {}", e.getMessage());
        }
    }

    private void cleanupTerminalResources(String podName) {
        log.info("🧹 Cleaning up terminal resources for podName: {}", podName);
        
        Thread reader = outputReaders.remove(podName);
        if (reader != null && reader.isAlive()) {
            reader.interrupt();
        }
        
        OutputStream out = sshOutputStreams.remove(podName);
        if (out != null) {
            try {
                out.close();
            } catch (IOException e) {
                log.debug("Error closing SSH output stream: {}", e.getMessage());
            }
        }
        
        ChannelShell channel = sshChannels.remove(podName);
        if (channel != null && channel.isConnected()) {
            channel.disconnect();
        }
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

    public void sendTerminalOutput(String podName, String output) {
        WebSocketSession session = podSessions.get(podName);
        
        if (session == null || !session.isOpen()) {
            log.debug("⚠️ No active session for podName: {}", podName);
            return;
        }

        try {
            session.sendMessage(new TextMessage(output));
            log.debug("📤 Sent terminal output to {}", podName);
        } catch (IOException e) {
            log.error("❌ Failed to send terminal output: {}", e.getMessage());
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