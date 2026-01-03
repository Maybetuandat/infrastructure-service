package com.example.infrastructure_service.service;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import com.example.infrastructure_service.dto.LabSessionReadyEvent;
import com.example.infrastructure_service.dto.UserLabSessionRequest;
import com.example.infrastructure_service.handler.PodLogWebSocketHandler;
import com.example.infrastructure_service.kafka.LabSessionReadyProducer;
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
    private final LabSessionReadyProducer labSessionReadyProducer;
    
    @Qualifier("longTimeoutApiClient")
    private final ApiClient apiClient;
    
    @Value("${ssh.default.username}")
    private String defaultUsername;

    @Value("${ssh.default.password}")
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
            log.info("STARTING USER LAB SESSION");
            log.info("Lab Session ID: {}", request.getLabSessionId());
            log.info("VM Name: {}", vmName);
            log.info("========================================");
            
            currentStep = 0;
            broadcastProgress(vmName, currentStep, totalSteps, "Waiting for WebSocket client to connect...");
            
            boolean wsConnected = webSocketHandler.waitForConnection(vmName, WEBSOCKET_TIMEOUT_SECONDS);
            
            if (!wsConnected) {
                log.warn("WebSocket connection timeout after {}s. Proceeding anyway.", WEBSOCKET_TIMEOUT_SECONDS);
            } else {
                log.info("WebSocket client connected successfully!");
            }
            
            currentStep = 1;
            broadcastProgress(vmName, currentStep, totalSteps, "Step 1: Creating Kubernetes resources (VM, PVC)...");
            vmService.createKubernetesResourcesForUserSession(request);
            broadcastSuccess(vmName, "Kubernetes resources created successfully");
            
            currentStep = 2;
            broadcastProgress(vmName, currentStep, totalSteps, "Step 2: Waiting for VM pod to be running...");
            V1Pod pod = discoveryService.waitForPodRunning(vmName, namespace, 600);
            String podName = pod.getMetadata().getName();
            log.info("Pod is running: {}", podName);
            broadcastSuccess(vmName, "Pod is running: " + podName);
            
            currentStep = 3;
            log.info("No setup steps required");
            broadcastInfo(vmName, "No setup steps required");
            
            currentStep = 4;
            broadcastProgress(vmName, currentStep, totalSteps, "Step 4: Pre-connecting SSH to VM...");
            preConnectAndCacheSSH(vmName, namespace, podName, request.getLabSessionId());
            broadcastSuccess(vmName, "SSH pre-connection successful");
            
            currentStep = 5;
            broadcastProgress(vmName, currentStep, totalSteps, "Step 5: Registering terminal session...");
            terminalSessionService.registerSession(request.getLabSessionId(), vmName, namespace, podName);
            
            int estimatedTimeMinutes = request.getEstimatedTimeMinutes() != null 
                ? request.getEstimatedTimeMinutes() 
                : 60;
            LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(estimatedTimeMinutes);
            String expiresAtStr = expiresAt.toString();
            log.info("========================================");
            log.info("USER LAB SESSION COMPLETED SUCCESSFULLY");
            log.info("Lab Session ID: {}", request.getLabSessionId());
            log.info("VM Name: {}", vmName);
            log.info("Pod Name: {}", podName);
            log.info("Expires At: {}", expiresAtStr);
            log.info("Terminal URL: /ws/terminal/{}", request.getLabSessionId());
            log.info("========================================");
            
            broadcastTerminalReady(vmName, request.getLabSessionId(), expiresAtStr);
            
            sendLabSessionReadyEvent(request.getLabSessionId(), vmName, podName, request.getLabId(), estimatedTimeMinutes);
            
        } catch (Exception e) {
            log.error("Error during user lab session setup: {}", e.getMessage(), e);
            broadcastError(vmName, "Setup failed: " + e.getMessage());
        }
    }
    
    private void sendLabSessionReadyEvent(int labSessionId, String vmName, String podName, Integer labId, int estimatedTimeMinutes) {
        try {
            LabSessionReadyEvent event = LabSessionReadyEvent.builder()
                .labSessionId(labSessionId)
                .vmName(vmName)
                .podName(podName)
                .labId(labId)
                .estimatedTimeMinutes(estimatedTimeMinutes)
                .build();

            labSessionReadyProducer.sendLabSessionReady(event);
            log.info("Sent lab session ready event for labSessionId: {}", labSessionId);
        } catch (Exception e) {
            log.error("Failed to send lab session ready event: {}", e.getMessage(), e);
        }
    }
    private void preConnectAndCacheSSH(String vmName, String namespace, String podName, int labSessionId) {
        log.info("Starting SSH pre-connection to VM: {}", vmName);
        
        JSch jsch = new JSch();
        String cacheKey = "lab-session-" + labSessionId;
        
        for (int attempt = 1; attempt <= SSH_MAX_RETRIES; attempt++) {
            Session sshSession = null;
            try {
                log.info("[{}] SSH pre-connection attempt {}/{}", vmName, attempt, SSH_MAX_RETRIES);
                webSocketHandler.broadcastLogToPod(vmName, "info", 
                    String.format("SSH connection attempt %d/%d", attempt, SSH_MAX_RETRIES), null);
                
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
                
                log.info("[{}] SSH pre-connected successfully on attempt {}", vmName, attempt);
                
                sshSessionCache.put(cacheKey, sshSession);
                log.info("[{}] SSH session cached with key: {}", vmName, cacheKey);
                
                return;
                
            } catch (Exception e) {
                log.warn("[{}] SSH pre-connection attempt {}/{} failed: {}", 
                    vmName, attempt, SSH_MAX_RETRIES, e.getMessage());
                
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
    
    private void broadcastTerminalReady(String vmName, int labSessionId, String expiresAt) {
        webSocketHandler.broadcastLogToPod(vmName, "terminal_ready",
            "Terminal is ready! You can now type commands...",
            Map.of(
                "labSessionId", labSessionId,
                "percentage", 100,
                "expiresAt", expiresAt
            ));

        webSocketHandler.setupTerminal(vmName, labSessionId);
    }
}