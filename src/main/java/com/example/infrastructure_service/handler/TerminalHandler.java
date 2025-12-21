package com.example.infrastructure_service.handler;

import com.example.infrastructure_service.service.TerminalSessionService;
import com.example.infrastructure_service.service.SetupExecutionService.K8sTunnelSocketFactory;
import com.example.infrastructure_service.service.SshSessionCache;
import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import io.kubernetes.client.openapi.ApiClient;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
@RequiredArgsConstructor
public class TerminalHandler extends TextWebSocketHandler {

    private final TerminalSessionService terminalSessionService;
    private final SshSessionCache sshSessionCache;
    
    @Qualifier("longTimeoutApiClient")
    private final ApiClient apiClient;

    @Value("${ssh.default.username:ubuntu}")
    private String defaultUsername;

    @Value("${ssh.default.password:1234}")
    private String defaultPassword;

    private final Map<String, Session> sshSessions = new ConcurrentHashMap<>();
    private final Map<String, ChannelShell> sshChannels = new ConcurrentHashMap<>();
    private final Map<String, Thread> outputReaders = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String labSessionIdStr = extractLabSessionId(session);
        if (labSessionIdStr == null) {
            log.error("❌ No labSessionId in WebSocket path");
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        int labSessionId = Integer.parseInt(labSessionIdStr);
        log.info("📡 Terminal WebSocket connected for labSessionId: {}", labSessionId);

        Map<String, String> sessionInfo = terminalSessionService.getSession(labSessionId);
        if (sessionInfo == null) {
            log.error("❌ No terminal session found for labSessionId: {}", labSessionId);
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Terminal session not registered"));
            return;
        }

        String vmName = sessionInfo.get("vmName");
        String namespace = sessionInfo.get("namespace");
        String podName = sessionInfo.get("podName");

        try {
            JSch jsch = new JSch();
            
            // Try to get cached session first
            String cacheKey = "lab-session-" + labSessionId;
            Session sshSession = sshSessionCache.get(cacheKey);
            
            if (sshSession != null && sshSession.isConnected()) {
                log.info("✅ Using cached SSH session for labSessionId: {}", labSessionId);
            } else {
                log.info("⚠️ No cached session found, creating new SSH connection for labSessionId: {}", labSessionId);
                
                sshSession = jsch.getSession(defaultUsername, vmName, 22);
                sshSession.setPassword(defaultPassword);
                sshSession.setConfig("StrictHostKeyChecking", "no");
                
                K8sTunnelSocketFactory socketFactory = new K8sTunnelSocketFactory(apiClient, namespace, podName);
                sshSession.setSocketFactory(socketFactory);
                sshSession.setTimeout(30000);
                sshSession.connect(30000);
            }

            ChannelShell channel = (ChannelShell) sshSession.openChannel("shell");
            channel.setPtyType("xterm");
            channel.setPtySize(80, 24, 640, 480);
            channel.connect();

            sshSessions.put(session.getId(), sshSession);
            sshChannels.put(session.getId(), channel);

            InputStream in = channel.getInputStream();
            OutputStream out = channel.getOutputStream();

            Thread reader = new Thread(() -> {
                try {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1 && session.isOpen()) {
                        String output = new String(buffer, 0, bytesRead);
                        session.sendMessage(new TextMessage(output));
                    }
                } catch (IOException e) {
                    log.debug("Terminal output reader stopped: {}", e.getMessage());
                }
            });
            reader.start();
            outputReaders.put(session.getId(), reader);

            session.getAttributes().put("sshOut", out);

            log.info("✅ SSH terminal session established for labSessionId: {}", labSessionId);

        } catch (Exception e) {
            log.error("❌ Failed to establish SSH session: {}", e.getMessage());
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        OutputStream out = (OutputStream) session.getAttributes().get("sshOut");
        if (out != null) {
            out.write(message.getPayload().getBytes());
            out.flush();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("🔌 Terminal WebSocket closed: {}", session.getId());

        Thread reader = outputReaders.remove(session.getId());
        if (reader != null && reader.isAlive()) {
            reader.interrupt();
        }

        ChannelShell channel = sshChannels.remove(session.getId());
        if (channel != null && channel.isConnected()) {
            channel.disconnect();
        }

        Session sshSession = sshSessions.remove(session.getId());
        if (sshSession != null && sshSession.isConnected()) {
            sshSession.disconnect();
        }
    }

    private String extractLabSessionId(WebSocketSession session) {
        String path = session.getUri().getPath();
        String[] parts = path.split("/");
        return parts.length > 0 ? parts[parts.length - 1] : null;
    }
}