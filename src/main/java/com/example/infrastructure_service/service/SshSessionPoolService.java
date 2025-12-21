package com.example.infrastructure_service.service;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import io.kubernetes.client.openapi.ApiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class SshSessionPoolService {

    private final ApiClient apiClient;

    @Value("${ssh.default.username:ubuntu}")
    private String defaultUsername;

    @Value("${ssh.default.password:1234}")
    private String defaultPassword;

    private final Map<String, Session> sessionPool = new ConcurrentHashMap<>();
    private final Map<String, Long> lastUsedTime = new ConcurrentHashMap<>();
    private final ScheduledExecutorService keepAliveExecutor = Executors.newScheduledThreadPool(1);

    private static final int SSH_CONNECT_TIMEOUT = 15000;
    private static final long SESSION_TIMEOUT_MS = 300000; // 5 minutes
    private static final int KEEPALIVE_INTERVAL_MS = 60000; // 1 minute

    // Manual constructor với @Qualifier
    public SshSessionPoolService(@Qualifier("longTimeoutApiClient") ApiClient apiClient) {
        this.apiClient = apiClient;
        
        // Start keepalive task
        keepAliveExecutor.scheduleAtFixedRate(
            this::keepAliveSessions,
            KEEPALIVE_INTERVAL_MS,
            KEEPALIVE_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
    }

    public Session getOrCreateSession(String namespace, String podName) throws Exception {
        String key = namespace + ":" + podName;
        
        Session session = sessionPool.get(key);
        
        if (session != null && session.isConnected()) {
            log.debug("✅ Reusing existing SSH session for {}", key);
            lastUsedTime.put(key, System.currentTimeMillis());
            return session;
        }

        log.info("🔄 Creating new SSH session for {}", key);
        session = createNewSession(namespace, podName);
        sessionPool.put(key, session);
        lastUsedTime.put(key, System.currentTimeMillis());
        
        return session;
    }

    private Session createNewSession(String namespace, String podName) throws Exception {
        JSch jsch = new JSch();
        
        Session session = jsch.getSession(defaultUsername, "localhost", 2222);
        session.setPassword(defaultPassword);
        session.setConfig("StrictHostKeyChecking", "no");
        
        // Enable keepalive
        session.setServerAliveInterval(30000); // 30 seconds
        session.setServerAliveCountMax(3);
        
        session.setSocketFactory(
            new SetupExecutionService.K8sTunnelSocketFactory(apiClient, namespace, podName)
        );
        
        session.connect(SSH_CONNECT_TIMEOUT);
        
        log.info("✅ SSH session created for {}:{}", namespace, podName);
        return session;
    }

    public void removeSession(String namespace, String podName) {
        String key = namespace + ":" + podName;
        
        Session session = sessionPool.remove(key);
        lastUsedTime.remove(key);
        
        if (session != null && session.isConnected()) {
            session.disconnect();
            log.info("🗑️ SSH session removed for {}", key);
        }
    }

    private void keepAliveSessions() {
        long now = System.currentTimeMillis();
        
        sessionPool.forEach((key, session) -> {
            try {
                Long lastUsed = lastUsedTime.get(key);
                
                // Remove old sessions
                if (lastUsed != null && (now - lastUsed) > SESSION_TIMEOUT_MS) {
                    log.info("⏰ Removing idle SSH session: {}", key);
                    sessionPool.remove(key);
                    lastUsedTime.remove(key);
                    if (session.isConnected()) {
                        session.disconnect();
                    }
                    return;
                }

                // Send keepalive
                if (session.isConnected()) {
                    session.sendKeepAliveMsg();
                    log.debug("💓 Keepalive sent for {}", key);
                } else {
                    log.warn("⚠️ Session disconnected, removing: {}", key);
                    sessionPool.remove(key);
                    lastUsedTime.remove(key);
                }
            } catch (Exception e) {
                log.error("❌ Error in keepalive for {}: {}", key, e.getMessage());
                sessionPool.remove(key);
                lastUsedTime.remove(key);
            }
        });
    }
}