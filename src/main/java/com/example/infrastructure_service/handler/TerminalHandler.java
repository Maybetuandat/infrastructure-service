package com.example.infrastructure_service.handler;

import com.example.infrastructure_service.service.KubernetesDiscoveryService;
import com.example.infrastructure_service.service.SetupExecutionService.K8sTunnelSocketFactory;
import com.example.infrastructure_service.service.SshSessionPoolService;
import com.example.infrastructure_service.service.TerminalSessionService;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
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
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
@RequiredArgsConstructor
public class TerminalHandler extends TextWebSocketHandler {

    private final KubernetesDiscoveryService discoveryService;
    private final SshSessionPoolService sshSessionPoolService;
    
    @Qualifier("longTimeoutApiClient")
    private final ApiClient apiClient;

    @Value("${ssh.default.username:ubuntu}")
    private String defaultUsername;

    @Value("${ssh.default.password:1234}")
    private String defaultPassword;

    private final Map<String, Session> sshSessions = new ConcurrentHashMap<>();
    private final Map<String, ChannelShell> sshChannels = new ConcurrentHashMap<>();
    private final Map<String, OutputStream> sshOutputStreams = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> activeConnections = new ConcurrentHashMap<>();

    private static final int SSH_CONNECT_TIMEOUT = 15000;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String wsSessionId = session.getId();
        activeConnections.put(wsSessionId, new AtomicBoolean(true));
        
        try {
            Object vmNameAttr = session.getAttributes().get("vmName");
            Object namespaceAttr = session.getAttributes().get("namespace");
            Object labSessionIdAttr = session.getAttributes().get("labSessionId");
            
            if (vmNameAttr == null || namespaceAttr == null) {
                log.error("❌ Missing vmName or namespace in session attributes");
                session.sendMessage(new TextMessage("❌ Terminal session not ready.\r\n"));
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

    private void connectViaK8sTunnel(WebSocketSession wsSession, String vmName, 
                                    String namespace, String wsSessionId) {
        try {
            AtomicBoolean isActive = activeConnections.get(wsSessionId);
            if (isActive == null || !isActive.get()) {
                log.warn("⚠️ [{}] Connection already closed, aborting", wsSessionId);
                return;
            }
            
            V1Pod pod = discoveryService.waitForPodRunning(vmName, namespace, 10);
            String podName = pod.getMetadata().getName();
            log.info("✅ [{}] Target Pod: {}", wsSessionId, podName);

            // Get cached session (instant!)
            Session jschSession = sshSessionPoolService.getOrCreateSession(namespace, podName);
            
            if (!jschSession.isConnected()) {
                throw new RuntimeException("Cached SSH session is not connected");
            }
            
            log.info("✅ [{}] Using cached SSH session (instant connect!)", wsSessionId);

            ChannelShell channel = (ChannelShell) jschSession.openChannel("shell");
            channel.setPty(true);
            channel.setPtyType("xterm");
            
            InputStream in = channel.getInputStream();
            OutputStream out = channel.getOutputStream();
            
            channel.connect(SSH_CONNECT_TIMEOUT);
            
            log.info("✅ [{}] Shell channel opened", wsSessionId);

            // Don't store session in map (use pooled session)
            sshChannels.put(wsSessionId, channel);
            sshOutputStreams.put(wsSessionId, out);

            wsSession.sendMessage(new TextMessage("\r\n✅ Connected to VM terminal!\r\n"));
            wsSession.sendMessage(new TextMessage("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\r\n\r\n"));

            CompletableFuture.runAsync(() -> {
                // Stream handling code...
            });

        } catch (Exception e) {
            log.error("❌ [{}] Terminal connection failed: {}", wsSessionId, e.getMessage());
            // Error handling...
        }
    }
    
    
    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        String wsSessionId = session.getId();
        AtomicBoolean isActive = activeConnections.get(wsSessionId);
        
        if (isActive == null || !isActive.get()) {
            return;
        }
        
        OutputStream out = sshOutputStreams.get(wsSessionId);
        
        if (out != null) {
            try {
                out.write(message.getPayload().getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                if (!e.getMessage().contains("Pipe closed") && 
                    !e.getMessage().contains("Stream closed")) {
                    log.error("❌ Error writing to SSH stream for session {}: {}", wsSessionId, e.getMessage());
                }
                cleanup(wsSessionId);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String wsSessionId = session.getId();
        log.info("🔌 WebSocket disconnected: {} | Status: {}", wsSessionId, status);
        cleanup(wsSessionId);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        String wsSessionId = session.getId();
        log.error("🚨 Transport error for session {}: {}", wsSessionId, exception.getMessage());
        cleanup(wsSessionId);
    }

    private void cleanup(String sessionId) {
        log.info("🧹 Cleaning up terminal session: {}", sessionId);

        AtomicBoolean isActive = activeConnections.remove(sessionId);
        if (isActive != null) {
            isActive.set(false);
        }

        OutputStream out = sshOutputStreams.remove(sessionId);
        if (out != null) {
            try {
                out.close();
            } catch (Exception e) {
                log.debug("Error closing output stream: {}", e.getMessage());
            }
        }

        ChannelShell channel = sshChannels.remove(sessionId);
        if (channel != null && channel.isConnected()) {
            try {
                channel.disconnect();
            } catch (Exception e) {
                log.debug("Error disconnecting channel: {}", e.getMessage());
            }
        }

        Session session = sshSessions.remove(sessionId);
        if (session != null && session.isConnected()) {
            try {
                session.disconnect();
            } catch (Exception e) {
                log.debug("Error disconnecting SSH session: {}", e.getMessage());
            }
        }

        log.info("✅ Cleanup complete for terminal session: {}", sessionId);
    }
}