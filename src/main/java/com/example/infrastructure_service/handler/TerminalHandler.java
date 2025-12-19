package com.example.infrastructure_service.handler;

import com.example.infrastructure_service.service.KubernetesDiscoveryService;
import com.example.infrastructure_service.service.SetupExecutionService.K8sTunnelSocketFactory;
import com.example.infrastructure_service.service.TerminalSessionService;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.models.V1Pod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

@Component
@Slf4j
@RequiredArgsConstructor
public class TerminalHandler extends TextWebSocketHandler {

    private final KubernetesDiscoveryService discoveryService;
    private final TerminalSessionService terminalSessionService;
    
    @Qualifier("longTimeoutApiClient")
    private final ApiClient apiClient;

    @Value("${ssh.default.username:ubuntu}")
    private String defaultUsername;

    @Value("${ssh.default.password:1234}")
    private String defaultPassword;

    private final Map<String, Session> sshSessions = new ConcurrentHashMap<>();
    private final Map<String, ChannelShell> sshChannels = new ConcurrentHashMap<>();
    private final Map<String, OutputStream> sshOutputStreams = new ConcurrentHashMap<>();

    // Configuration
    private static final int SSH_MAX_RETRIES = 20;
    private static final long SSH_RETRY_DELAY_MS = 3000; // 3 seconds
    private static final int SSH_CONNECT_TIMEOUT = 15000; // 15 seconds

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String wsSessionId = session.getId();
        try {
            Object vmNameAttr = session.getAttributes().get("vmName");
            Object namespaceAttr = session.getAttributes().get("namespace");
            Object labSessionIdAttr = session.getAttributes().get("labSessionId");
            
            if (vmNameAttr == null || namespaceAttr == null) {
                log.error("❌ Missing vmName or namespace in session attributes");
                session.sendMessage(new TextMessage("❌ Terminal session not ready. Please wait for VM creation to complete.\r\n"));
                session.close();
                return;
            }

            String vmName = vmNameAttr.toString();
            String namespace = namespaceAttr.toString();
            Integer labSessionId = (Integer) labSessionIdAttr;

            log.info("🔌 Terminal WebSocket connected: wsSessionId={}, labSessionId={}, VM={}", 
                wsSessionId, labSessionId, vmName);
            
            session.sendMessage(new TextMessage("🔗 Connecting to VM terminal...\r\n"));
            
            connectViaK8sTunnel(session, vmName, namespace, wsSessionId);

        } catch (Exception e) {
            log.error("❌ Terminal connection failed: {}", e.getMessage(), e);
            cleanup(wsSessionId);
        }
    }

    /**
     * Kết nối SSH sử dụng K8sTunnelSocketFactory giống như SetupExecutionService
     */
    private void connectViaK8sTunnel(WebSocketSession wsSession, String vmName, 
                                     String namespace, String wsSessionId) {
        Session jschSession = null;
        
        try {
            // STEP 1: Wait for Pod Running
            wsSession.sendMessage(new TextMessage("⏳ Waiting for VM pod to be ready...\r\n"));
            V1Pod pod = discoveryService.waitForPodRunning(vmName, namespace, 120);
            String podName = pod.getMetadata().getName();
            log.info("✅ [{}] Target Pod: {}", wsSessionId, podName);
            wsSession.sendMessage(new TextMessage("✅ VM pod is running: " + podName + "\r\n"));

            // STEP 2: Connect SSH with K8sTunnelSocketFactory and retry
            wsSession.sendMessage(new TextMessage("⏳ Establishing SSH connection...\r\n"));
            
            JSch jsch = new JSch();
            jschSession = connectSshWithRetry(wsSession, jsch, namespace, podName, 
                                              SSH_MAX_RETRIES, SSH_RETRY_DELAY_MS, wsSessionId);
            
            if (jschSession == null || !jschSession.isConnected()) {
                throw new RuntimeException("Failed to establish SSH connection");
            }
            
            log.info("✅ [{}] SSH session established successfully", wsSessionId);
            wsSession.sendMessage(new TextMessage("✅ SSH connection established!\r\n"));

            // STEP 3: Open shell channel
            wsSession.sendMessage(new TextMessage("⏳ Opening terminal shell...\r\n"));
            
            ChannelShell channel = (ChannelShell) jschSession.openChannel("shell");
            channel.setPty(true);
            channel.setPtyType("xterm");
            
            InputStream in = channel.getInputStream();
            OutputStream out = channel.getOutputStream();
            
            channel.connect(SSH_CONNECT_TIMEOUT);
            
            log.info("✅ [{}] Shell channel opened successfully", wsSessionId);

            // Store references
            sshSessions.put(wsSessionId, jschSession);
            sshChannels.put(wsSessionId, channel);
            sshOutputStreams.put(wsSessionId, out);

            // Success message
            wsSession.sendMessage(new TextMessage("\r\n✅ Connected to VM terminal!\r\n"));
            wsSession.sendMessage(new TextMessage("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\r\n\r\n"));

            // STEP 4: Stream SSH output to WebSocket
            final Session finalJschSession = jschSession;
            CompletableFuture.runAsync(() -> {
                try {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        String output = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                        wsSession.sendMessage(new TextMessage(output));
                    }
                } catch (Exception e) {
                    log.debug("[{}] SSH output stream closed: {}", wsSessionId, e.getMessage());
                } finally {
                    cleanup(wsSessionId);
                }
            });

        } catch (Exception e) {
            log.error("❌ [{}] Terminal connection failed: {}", wsSessionId, e.getMessage());
            try {
                if (jschSession != null && jschSession.isConnected()) {
                    jschSession.disconnect();
                }
                
                wsSession.sendMessage(new TextMessage(
                    "\r\n❌ Failed to connect to terminal: " + e.getMessage() + "\r\n"
                ));
                wsSession.sendMessage(new TextMessage(
                    "💡 Tip: VM might still be booting. Please wait and refresh the page to try again.\r\n"
                ));
                wsSession.close(CloseStatus.SERVER_ERROR);
            } catch (IOException ex) {
                log.error("Failed to send error message to WebSocket", ex);
            }
        }
    }

    /**
     * Kết nối SSH với retry logic - tương tự SetupExecutionService
     */
    private Session connectSshWithRetry(WebSocketSession wsSession, JSch jsch, 
                                       String namespace, String podName,
                                       int maxRetries, long delayMs, String wsSessionId) 
            throws Exception {
        
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("🔄 [{}] SSH connection attempt {}/{}", wsSessionId, attempt, maxRetries);
                
                // Send progress to WebSocket
                wsSession.sendMessage(new TextMessage(
                    String.format("⏳ SSH connection attempt %d/%d...\r\n", attempt, maxRetries)
                ));
                
                // Create new session
                Session session = jsch.getSession(defaultUsername, "localhost", 2222);
                session.setPassword(defaultPassword);
                session.setConfig("StrictHostKeyChecking", "no");
                
                // Use K8sTunnelSocketFactory - KEY DIFFERENCE
                session.setSocketFactory(new K8sTunnelSocketFactory(apiClient, namespace, podName));
                
                // Attempt connection
                session.connect(SSH_CONNECT_TIMEOUT);
                
                log.info("✅ [{}] SSH connected successfully on attempt {}", wsSessionId, attempt);
                return session;
                
            } catch (JSchException e) {
                lastException = e;
                log.warn("⚠️ [{}] SSH connection attempt {}/{} failed: {}", 
                        wsSessionId, attempt, maxRetries, e.getMessage());
                
                if (attempt < maxRetries) {
                    log.info("[{}] Waiting {}ms before retry...", wsSessionId, delayMs);
                    
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("SSH connection interrupted", ie);
                    }
                } else {
                    // Last attempt failed
                    log.error("❌ [{}] All {} SSH connection attempts failed", wsSessionId, maxRetries);
                    throw new RuntimeException(
                        String.format("SSH connection failed after %d attempts", maxRetries), 
                        lastException
                    );
                }
            }
        }
        
        throw new RuntimeException("Failed to connect SSH after retries", lastException);
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        String wsSessionId = session.getId();
        OutputStream out = sshOutputStreams.get(wsSessionId);
        
        if (out != null) {
            try {
                out.write(message.getPayload().getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                log.error("❌ Error writing to SSH stream for session {}: {}", wsSessionId, e.getMessage());
                cleanup(wsSessionId);
            }
        } else {
            log.warn("⚠️ No SSH output stream found for session {}", wsSessionId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String wsSessionId = session.getId();
        log.info("🔌 WebSocket client disconnected: {} | Status: {}", wsSessionId, status);
        cleanup(wsSessionId);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        String wsSessionId = session.getId();
        log.error("🚨 Transport error for session {}: {}", wsSessionId, exception.getMessage());
        cleanup(wsSessionId);
    }

    /**
     * Cleanup all resources for a session
     */
    private void cleanup(String sessionId) {
        log.info("🧹 Cleaning up terminal session: {}", sessionId);

        // Close output stream
        OutputStream out = sshOutputStreams.remove(sessionId);
        if (out != null) {
            try {
                out.close();
            } catch (Exception e) {
                log.debug("Error closing output stream: {}", e.getMessage());
            }
        }

        // Disconnect SSH channel
        ChannelShell channel = sshChannels.remove(sessionId);
        if (channel != null && channel.isConnected()) {
            channel.disconnect();
        }

        // Disconnect SSH session
        Session session = sshSessions.remove(sessionId);
        if (session != null && session.isConnected()) {
            session.disconnect();
        }

        log.info("✅ Cleanup complete for terminal session: {}", sessionId);
    }
}