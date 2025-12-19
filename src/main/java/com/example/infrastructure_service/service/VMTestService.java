package com.example.infrastructure_service.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import com.example.infrastructure_service.dto.LabTestRequest;
import com.example.infrastructure_service.utils.PodLogWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class VMTestService {
    
    private final VMService vmService;
    private final KubernetesDiscoveryService discoveryService;
    private final SetupExecutionService setupExecutionService;
    private final PodLogWebSocketHandler webSocketHandler;
    
    private static final int WEBSOCKET_TIMEOUT_SECONDS = 30;
    
    @Async
    public void handleLabTestRequest(LabTestRequest request) {
        String vmName = request.getTestVmName();
        String namespace = request.getNamespace();
        
        try {
            log.info("========================================");
            log.info("🚀 STARTING LAB TEST WITH WEBSOCKET WAIT");
            log.info("Lab ID: {}", request.getLabId());
            log.info("Test VM Name: {}", vmName);
            log.info("========================================");
            
            // ✅ STEP 0: Đợi WebSocket connection trước khi bắt đầu tạo VM
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
                    "🔗 WebSocket connected. Starting test VM creation...", null);
            }
            
            // Small delay để client có thể render UI
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // ✅ STEP 1: Create VM resources
            log.info("📦 Step 1: Creating VM resources...");
            webSocketHandler.broadcastLogToPod(vmName, "info", 
                "📦 Creating VM resources...", null);
            
            vmService.createKubernetesResourcesForTest(request);
            
            webSocketHandler.broadcastLogToPod(vmName, "success", 
                "✅ VM resources created successfully", null);
            
            // ✅ STEP 2: Wait for VM to be ready
            log.info("⏳ Step 2: Waiting for VM to be ready...");
            webSocketHandler.broadcastLogToPod(vmName, "info", 
                "⏳ Waiting for VM pod to be ready...", null);
            
            var pod = discoveryService.waitForPodRunning(vmName, namespace, 1200);
            String podName = pod.getMetadata().getName();
            
            log.info("✅ Step 3: VM Pod is running: {}", podName);
            webSocketHandler.broadcastLogToPod(vmName, "success", 
                "✅ Test VM is now running: " + podName, null);
            
            // ✅ STEP 3: Execute setup steps (nếu có)
            if (request.getSetupStepsJson() != null && !request.getSetupStepsJson().isEmpty()) {
                log.info("⚙️ Step 4: Executing setup steps...");
                
                webSocketHandler.broadcastLogToPod(vmName, "info", 
                    "⚙️ Starting setup steps execution...", null);
                
                setupExecutionService.executeSetupStepsForTest(request, podName);
                
                webSocketHandler.broadcastLogToPod(vmName, "success", 
                    "✅ Setup completed successfully!", null);
            } else {
                log.info("ℹ️ No setup steps required");
                webSocketHandler.broadcastLogToPod(vmName, "info", 
                    "ℹ️ No setup steps required. Lab is ready!", null);
            }
            
            webSocketHandler.broadcastLogToPod(vmName, "success", 
                "✅ Lab test environment is ready!", null);
            
            log.info("========================================");
            log.info("✅ LAB TEST COMPLETED SUCCESSFULLY");
            log.info("Test VM Name: {}", vmName);
            log.info("Pod Name: {}", podName);
            log.info("========================================");
            
        } catch (Exception e) {
            log.error("❌ Error during lab test: {}", e.getMessage(), e);
            webSocketHandler.broadcastLogToPod(vmName, "error", 
                "❌ Failed to create test VM: " + e.getMessage(), null);
        }
    }
}