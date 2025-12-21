package com.example.infrastructure_service.service;

import java.util.Map;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import com.example.infrastructure_service.dto.UserLabSessionRequest;
import com.example.infrastructure_service.utils.PodLogWebSocketHandler;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.example.infrastructure_service.service.SetupExecutionService.K8sTunnelSocketFactory;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.models.V1Pod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;

@Service
@Slf4j
@RequiredArgsConstructor
public class VMUserSessionService {
    
    private final VMService vmService;
    private final KubernetesDiscoveryService discoveryService;
    private final SetupExecutionService setupExecutionService;
    private final PodLogWebSocketHandler webSocketHandler;
    private final TerminalSessionService terminalSessionService;
    private final SshSessionCache sshSessionCache;
    
    @Qualifier("longTimeoutApiClient")
    private final ApiClient apiClient;
    
    @Value("${ssh.default.username:ubuntu}")
    private String defaultUsername;

    @Value("${ssh.default.password:1234}")
    private String defaultPassword;
    
    private static final int WEBSOCKET_TIMEOUT_SECONDS = 30;
    private static final int SSH_MAX_RETRIES = 20;
    private static final long SSH_RETRY_DELAY_MS = 3000;
    
    @Async
    public void handleUserLabSessionRequest(UserLabSessionRequest request) {
        String vmName = request.getVmName();
        String namespace = request.getNamespace();
        int totalSteps = 5;
        int currentStep = 0;
        
        try {
            log.info("========================================");
            log.info("🚀 STARTING USER LAB SESSION");
            log.info("Lab Session ID: {}", request.getLabSessionId());
            log.info("VM Name: {}", vmName);
            log.info("========================================");
            
            // Step 0: Wait for WebSocket connection
            currentStep = 0;
            broadcastProgress(vmName, currentStep, totalSteps, "⏳ Waiting for WebSocket client to connect...");
            
            boolean wsConnected = webSocketHandler.waitForConnection(vmName, WEBSOCKET_TIMEOUT_SECONDS);
            
            if (!wsConnected) {
                log.warn("⚠️ WebSocket connection timeout after {}s. Proceeding anyway.", WEBSOCKET_TIMEOUT_SECONDS);
            } else {
                log.info("✅ WebSocket client connected successfully!");
            }
            
            // Step 1: Create Kubernetes resources
            currentStep = 1;
            broadcastProgress(vmName, currentStep, totalSteps, "🔧 Step 1: Creating Kubernetes resources (VM, PVC)...");
            vmService.createKubernetesResourcesForUserSession(request);
            broadcastSuccess(vmName, "✅ Kubernetes resources created successfully");
            
            // Step 2: Wait for VM pod to be running
            currentStep = 2;
            broadcastProgress(vmName, currentStep, totalSteps, "🔧 Step 2: Waiting for VM pod to be running...");
            V1Pod pod = discoveryService.waitForPodRunning(vmName, namespace, 600);
            String podName = pod.getMetadata().getName();
            log.info("✅ Pod is running: {}", podName);
            broadcastSuccess(vmName, "✅ Pod is running: " + podName);
            
            // Step 3: Skip setup (no setup steps)
            currentStep = 3;
            log.info("ℹ️ No setup steps required");
            broadcastInfo(vmName, "ℹ️ No setup steps required");
            
            // Step 4: Pre-connect SSH (warm up connection, DO NOT disconnect)
            currentStep = 4;
            broadcastProgress(vmName, currentStep, totalSteps, "🔧 Step 4: Pre-connecting SSH to VM...");
            preConnectAndCacheSSH(vmName, namespace, podName, request.getLabSessionId());
            broadcastSuccess(vmName, "✅ SSH pre-connection successful");
            
            // Step 5: Register terminal session
            currentStep = 5;
            broadcastProgress(vmName, currentStep, totalSteps, "🔧 Step 5: Registering terminal session...");
            terminalSessionService.registerSession(request.getLabSessionId(), vmName, namespace, podName);
            
            // COMPLETION - Send terminal_ready
            log.info("========================================");
            log.info("✅ USER LAB SESSION COMPLETED SUCCESSFULLY");
            log.info("Lab Session ID: {}", request.getLabSessionId());
            log.info("VM Name: {}", vmName);
            log.info("Pod Name: {}", podName);
            log.info("Terminal URL: /ws/terminal/{}", request.getLabSessionId());
            log.info("========================================");
            
            broadcastTerminalReady(vmName, request.getLabSessionId());
            
        } catch (Exception e) {
            log.error("❌ Error during user lab session setup: {}", e.getMessage(), e);
            broadcastError(vmName, "❌ Setup failed: " + e.getMessage());
        }
    }
    
    private void preConnectAndCacheSSH(String vmName, String namespace, String podName, int labSessionId) {
        log.info("🔄 Starting SSH pre-connection to VM: {}", vmName);
        
        JSch jsch = new JSch();
        String cacheKey = "lab-session-" + labSessionId;
        
        for (int attempt = 1; attempt <= SSH_MAX_RETRIES; attempt++) {
            Session sshSession = null;
            try {
                log.info("🔄 [{}] SSH pre-connection attempt {}/{}", vmName, attempt, SSH_MAX_RETRIES);
                webSocketHandler.broadcastLogToPod(vmName, "info", 
                    String.format("🔄 SSH connection attempt %d/%d", attempt, SSH_MAX_RETRIES), null);
                
                sshSession = jsch.getSession(defaultUsername, vmName, 22);
                sshSession.setPassword(defaultPassword);
                sshSession.setConfig("StrictHostKeyChecking", "no");
                
                K8sTunnelSocketFactory socketFactory = new K8sTunnelSocketFactory(
                    apiClient, 
                    namespace, 
                    podName
                );
                sshSession.setSocketFactory(socketFactory);
                sshSession.setTimeout(10000);
                sshSession.connect(10000);
                
                log.info("✅ [{}] SSH pre-connected successfully on attempt {}", vmName, attempt);
                
                // CRITICAL: Cache session for reuse, DO NOT disconnect
                sshSessionCache.put(cacheKey, sshSession);
                log.info("💾 [{}] SSH session cached with key: {}", vmName, cacheKey);
                
                return;
                
            } catch (Exception e) {
                log.warn("⚠️ [{}] SSH pre-connection attempt {}/{} failed: {}", 
                    vmName, attempt, SSH_MAX_RETRIES, e.getMessage());
                
                // Cleanup failed session
                if (sshSession != null && sshSession.isConnected()) {
                    try {
                        sshSession.disconnect();
                    } catch (Exception ex) {
                        log.debug("Error disconnecting failed session: {}", ex.getMessage());
                    }
                }
                
                if (attempt >= SSH_MAX_RETRIES) {
                    throw new RuntimeException("SSH pre-connection failed after " + SSH_MAX_RETRIES + " attempts");
                }
                
                try {
                    Thread.sleep(SSH_RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("SSH pre-connection interrupted", ie);
                }
            }
        }
    }
    
    private void broadcastProgress(String vmName, int currentStep, int totalSteps, String message) {
        int percentage = (int) ((currentStep * 100.0) / totalSteps);
        webSocketHandler.broadcastLogToPod(vmName, "progress", message, 
            Map.of("currentStep", currentStep, "totalSteps", totalSteps, "percentage", percentage));
    }
    
    private void broadcastInfo(String vmName, String message) {
        webSocketHandler.broadcastLogToPod(vmName, "info", message, null);
    }
    
    private void broadcastSuccess(String vmName, String message) {
        webSocketHandler.broadcastLogToPod(vmName, "success", message, null);
    }
    
    private void broadcastError(String vmName, String message) {
        webSocketHandler.broadcastLogToPod(vmName, "error", message, null);
    }
    
   
    private void broadcastTerminalReady(String vmName, int labSessionId) {
    webSocketHandler.broadcastLogToPod(vmName, "terminal_ready", 
        "🎉 Terminal is ready! You can now type commands...", 
        Map.of("labSessionId", labSessionId, "percentage", 100));
    webSocketHandler.enableTerminalMode(vmName, labSessionId);

    }
}