package com.example.infrastructure_service.service;

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
    
    private static final int WEBSOCKET_TIMEOUT_SECONDS = 30;
    
    @Async
    public void handleUserLabSessionRequest(UserLabSessionRequest request) {
        String vmName = request.getVmName();
        String namespace = request.getNamespace();
        
        try {
            log.info("========================================");
            log.info("🚀 STARTING USER LAB SESSION");
            log.info("Lab Session ID: {}", request.getLabSessionId());
            log.info("VM Name: {}", vmName);
            log.info("========================================");
            
            log.info("⏳ Step 0: Waiting for WebSocket client to connect...");
            
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
            
            log.info("📦 Step 1: Creating VM resources...");
            webSocketHandler.broadcastLogToPod(vmName, "info", 
                "📦 Creating VM resources...", null);
            
            vmService.createKubernetesResourcesForUserSession(request);
            
            webSocketHandler.broadcastLogToPod(vmName, "success", 
                "✅ VM resources created successfully", null);
            
            log.info("⏳ Step 2: Waiting for VM to be ready...");
            webSocketHandler.broadcastLogToPod(vmName, "info", 
                "⏳ Waiting for VM pod to be ready...", null);
            
            var pod = discoveryService.waitForPodRunning(vmName, namespace, 1200);
            String podName = pod.getMetadata().getName();
            
            log.info("✅ Step 3: VM Pod is running: {}", podName);
            webSocketHandler.broadcastLogToPod(vmName, "success", 
                "✅ VM is now running: " + podName, null);
            
            if (request.getSetupStepsJson() != null && !request.getSetupStepsJson().isEmpty()) {
                log.info("⚙️ Step 4: Executing setup steps...");
                
                webSocketHandler.broadcastLogToPod(vmName, "info", 
                    "⚙️ Starting setup steps execution...", null);
                
                setupExecutionService.executeSetupStepsForUserSession(request, podName);
                
                webSocketHandler.broadcastLogToPod(vmName, "success", 
                    "✅ Setup completed successfully!", null);
            } else {
                log.info("ℹ️ No setup steps required");
                webSocketHandler.broadcastLogToPod(vmName, "info", 
                    "ℹ️ No setup steps required. Lab is ready!", null);
            }
            
            webSocketHandler.broadcastLogToPod(vmName, "success", 
                "✅ Lab environment is ready!", null);
            
            log.info("========================================");
            log.info("✅ USER LAB SESSION COMPLETED SUCCESSFULLY");
            log.info("VM Name: {}", vmName);
            log.info("Pod Name: {}", podName);
            log.info("========================================");
            
        } catch (Exception e) {
            log.error("❌ Error during user lab session: {}", e.getMessage(), e);
            webSocketHandler.broadcastLogToPod(vmName, "error", 
                "❌ Failed to create VM: " + e.getMessage(), null);
        }
    }
}
