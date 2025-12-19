
package com.example.infrastructure_service.service;

import java.util.Map;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import com.example.infrastructure_service.dto.UserLabSessionRequest;
import com.example.infrastructure_service.utils.PodLogWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class VMUserSessionService {
    
    private final VMService vmService;
    private final KubernetesDiscoveryService discoveryService;
    private final SetupExecutionService setupExecutionService;
    private final PodLogWebSocketHandler webSocketHandler;
    private final TerminalSessionService terminalSessionService;
    
    private static final int WEBSOCKET_TIMEOUT_SECONDS = 30;
    
    @Async
    public void handleUserLabSessionRequest(UserLabSessionRequest request) {
        String vmName = request.getVmName();
        String namespace = request.getNamespace();
        int totalSteps = 5; // Total number of major steps
        int currentStep = 0;
        
        try {
            log.info("========================================");
            log.info("🚀 STARTING USER LAB SESSION");
            log.info("Lab Session ID: {}", request.getLabSessionId());
            log.info("VM Name: {}", vmName);
            log.info("========================================");
            
            // Step 0: Wait for WebSocket connection (0%)
            currentStep = 0;
            broadcastProgress(vmName, currentStep, totalSteps, "⏳ Waiting for WebSocket client to connect...");
            
            boolean wsConnected = webSocketHandler.waitForConnection(vmName, WEBSOCKET_TIMEOUT_SECONDS);
            
            if (!wsConnected) {
                log.warn("⚠️ WebSocket connection timeout after {}s. Proceeding anyway (graceful degradation).", 
                    WEBSOCKET_TIMEOUT_SECONDS);
                webSocketHandler.broadcastLogToPod(vmName, "warning", 
                    "⚠️ WebSocket connection timeout. Logs may be incomplete.", null);
            } else {
                log.info("✅ WebSocket client connected successfully!");
                webSocketHandler.broadcastLogToPod(vmName, "connection", 
                    "🔗 WebSocket connected. Starting VM creation...", null);
            }
            
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Step 1: Create PVC (20%)
            currentStep = 1;
            broadcastProgress(vmName, currentStep, totalSteps, "📦 Creating PersistentVolumeClaim...");
            log.info("📦 Step 1: Creating VM resources...");
            
            vmService.createKubernetesResourcesForUserSession(request);
            
            webSocketHandler.broadcastLogToPod(vmName, "success", 
                "✅ VM resources created successfully", null);
            
            // Step 2: Wait for Pod Running (40%)
            currentStep = 2;
            broadcastProgress(vmName, currentStep, totalSteps, "⏳ Waiting for VM pod to be ready...");
            log.info("⏳ Step 2: Waiting for VM to be ready...");
            
            var pod = discoveryService.waitForPodRunning(vmName, namespace, 1200);
            String podName = pod.getMetadata().getName();
            
            log.info("✅ Step 3: VM Pod is running: {}", podName);
            webSocketHandler.broadcastLogToPod(vmName, "success", 
                "✅ VM is now running: " + podName, null);
            
            // Step 3: Execute Setup Steps (60%)
            currentStep = 3;
            if (request.getSetupStepsJson() != null && !request.getSetupStepsJson().isEmpty()) {
                broadcastProgress(vmName, currentStep, totalSteps, "⚙️ Executing setup steps...");
                log.info("⚙️ Step 4: Executing setup steps...");
                
                webSocketHandler.broadcastLogToPod(vmName, "info", 
                    "⚙️ Starting setup steps execution...", null);
                
                setupExecutionService.executeSetupStepsForUserSession(request, podName);
                
                webSocketHandler.broadcastLogToPod(vmName, "success", 
                    "✅ Setup completed successfully!", null);
            } else {
                log.info("ℹ️ No setup steps required");
                webSocketHandler.broadcastLogToPod(vmName, "info", 
                    "ℹ️ No setup steps required. Skipping...", null);
            }
            
            // Step 4: Register Terminal Session (80%)
            currentStep = 4;
            broadcastProgress(vmName, currentStep, totalSteps, "🔧 Registering terminal session...");
            log.info("🔧 Step 5: Registering terminal session...");
            
            terminalSessionService.registerSession(
                request.getLabSessionId(),
                vmName,
                namespace
            );
            
            webSocketHandler.broadcastLogToPod(vmName, "success", 
                "✅ Terminal session registered", null);
            
            // Step 5: Complete (100%)
            currentStep = 5;
            broadcastProgress(vmName, currentStep, totalSteps, "✅ Lab environment is ready!");
            
            // Notify that terminal is available
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