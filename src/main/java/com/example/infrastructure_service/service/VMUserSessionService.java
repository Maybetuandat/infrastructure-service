
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
        int totalSteps = 6;
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
            
            // Step 1: Create Kubernetes Resources
            currentStep = 1;
            broadcastProgress(vmName, currentStep, totalSteps, "⏳ Creating Kubernetes resources...");
            log.info("🔧 Step 1: Creating Kubernetes resources (VM, PVC)...");
            
            vmService.createKubernetesResourcesForUserSession(request);
            
            // Step 2: Wait for VM Pod Running
            currentStep = 2;
            broadcastProgress(vmName, currentStep, totalSteps, "⏳ Waiting for VM to start...");
            log.info("🔧 Step 2: Waiting for VM pod to be running...");
            
            V1Pod pod = discoveryService.waitForPodRunning(vmName, namespace, 120);
            String podName = pod.getMetadata().getName();
            log.info("✅ Pod is running: {}", podName);
            
            // Step 3: Execute Setup Steps if any
            if (request.getSetupStepsJson() != null && !request.getSetupStepsJson().trim().isEmpty() 
                && !request.getSetupStepsJson().equals("[]")) {
                
                currentStep = 3;
                broadcastProgress(vmName, currentStep, totalSteps, "⏳ Executing setup steps...");
                log.info("🔧 Step 3: Executing setup steps...");
                
                setupExecutionService.executeSetupStepsForUserSession(request, podName);
                log.info("✅ Setup steps completed successfully");
            } else {
                log.info("ℹ️ No setup steps required");
            }
            
            // Step 4: Pre-connect SSH to VM
            currentStep = 4;
            broadcastProgress(vmName, currentStep, totalSteps, "⏳ Preparing terminal connection...");
            log.info("🔧 Step 4: Pre-connecting SSH to VM...");
            
            Session testSession = preConnectSshWithRetry(vmName, namespace, podName);
            
            if (testSession != null && testSession.isConnected()) {
                testSession.disconnect();
                log.info("✅ SSH pre-connection successful");
            } else {
                throw new RuntimeException("Failed to pre-connect SSH to VM");
            }
            
            // Step 5: Register Terminal Session
            currentStep = 5;
            broadcastProgress(vmName, currentStep, totalSteps, "🔧 Registering terminal session...");
            log.info("🔧 Step 5: Registering terminal session...");
            
            terminalSessionService.registerSession(
                request.getLabSessionId(),
                vmName,
                namespace
            );
            
            // Step 6: Complete
            currentStep = 6;
            broadcastProgress(vmName, currentStep, totalSteps, "✅ Lab environment is ready!");
            
            webSocketHandler.broadcastLogToPod(vmName, "terminal_ready", 
                "🎉 Terminal is now available! Connect to: /ws/terminal/" + request.getLabSessionId(), 
                Map.of(
                    "labSessionId", request.getLabSessionId(),
                    "terminalUrl", "/ws/terminal/" + request.getLabSessionId()
                ));
            
            log.info("========================================");
            log.info("✅ USER LAB SESSION COMPLETED SUCCESSFULLY");
            log.info("Lab Session ID: {}", request.getLabSessionId());
            log.info("VM Name: {}", vmName);
            log.info("Pod Name: {}", podName);
            log.info("Terminal URL: /ws/terminal/{}", request.getLabSessionId());
            log.info("========================================");
            
        } catch (Exception e) {
            log.error("❌ Error during user lab session: {}", e.getMessage(), e);
            webSocketHandler.broadcastLogToPod(vmName, "error", 
                "❌ Failed to create VM: " + e.getMessage(), 
                Map.of("error", e.getMessage()));
        }
    }
    
    private Session preConnectSshWithRetry(String vmName, String namespace, String podName) {
        JSch jsch = new JSch();
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= SSH_MAX_RETRIES; attempt++) {
            try {
                log.info("🔄 [{}] SSH pre-connection attempt {}/{}", vmName, attempt, SSH_MAX_RETRIES);
                
                Session session = jsch.getSession(defaultUsername, "localhost", 2222);
                session.setPassword(defaultPassword);
                session.setConfig("StrictHostKeyChecking", "no");
                
                session.setSocketFactory(new K8sTunnelSocketFactory(apiClient, namespace, podName));
                
                session.connect(15000);
                
                log.info("✅ [{}] SSH pre-connected successfully on attempt {}", vmName, attempt);
                return session;
                
            } catch (Exception e) {
                lastException = e;
                log.warn("⚠️ [{}] SSH pre-connection attempt {}/{} failed: {}", 
                        vmName, attempt, SSH_MAX_RETRIES, e.getMessage());
                
                if (attempt < SSH_MAX_RETRIES) {
                    try {
                        Thread.sleep(SSH_RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("SSH pre-connection interrupted", ie);
                    }
                }
            }
        }
        
        log.error("❌ [{}] All {} SSH pre-connection attempts failed", vmName, SSH_MAX_RETRIES);
        throw new RuntimeException(
            String.format("SSH pre-connection failed after %d attempts", SSH_MAX_RETRIES), 
            lastException
        );
    }
    
    private void broadcastProgress(String vmName, int currentStep, int totalSteps, String message) {
        int percentage = (currentStep * 100) / totalSteps;
        webSocketHandler.broadcastLogToPod(vmName, "progress", 
            message, 
            Map.of(
                "currentStep", currentStep,
                "totalSteps", totalSteps,
                "percentage", percentage
            ));
    }
}