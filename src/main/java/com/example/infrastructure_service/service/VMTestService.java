package com.example.infrastructure_service.service;

import com.example.infrastructure_service.dto.InstanceTypeDTO;
import com.example.infrastructure_service.dto.LabTestRequest;
import com.example.infrastructure_service.utils.PodLogWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class VMTestService {

    private final PodLogWebSocketHandler webSocketHandler;
    private final VMTestOrchestrationService orchestrationService;

    @Async
    public void handleLabTestRequest(LabTestRequest request) {
        String testVmName = request.getTestVmName();
        
        log.info("===========================================");
        log.info(" [ASYNC] STARTING LAB TEST EXECUTION");
        log.info(" Lab ID: {}", request.getLabId());
        log.info(" Test VM Name: {}", testVmName);
        log.info("===========================================");
        
        log.info("Step 1: Waiting for WebSocket client to connect...");
        
        boolean wsConnected = webSocketHandler.waitForConnection(testVmName, 30);

        if (!wsConnected) {
            log.error("❌ WebSocket connection timeout for VM: {}", testVmName);
            webSocketHandler.broadcastLogToPod(testVmName, "error",
                    "❌ WebSocket connection timeout",
                    Map.of("testVmName", testVmName));
            return;
        }

        log.info("✅ WebSocket client connected successfully!");
        
        webSocketHandler.broadcastLogToPod(testVmName, "connection",
                "🔗 WebSocket connected successfully. Starting test...",
                Map.of("testVmName", testVmName));

        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        webSocketHandler.broadcastLogToPod(testVmName, "start",
                String.format("🚀 Starting test for lab: %s (ID: %d)", request.getLabTitle(), request.getLabId()),
                Map.of("labId", request.getLabId(), "labTitle", request.getLabTitle()));

        boolean success = orchestrationService.executeTestWorkflow(
                request.getLabId(),
                request.getLabTitle(),
                testVmName,
                request.getNamespace(),
                1800,
                request.getInstanceType()
        );

        if (success) {
            webSocketHandler.broadcastLogToPod(testVmName, "success",
                    "✅ Test completed successfully!",
                    Map.of("status", "COMPLETED"));
        } else {
            webSocketHandler.broadcastLogToPod(testVmName, "error",
                    "❌ Test failed!",
                    Map.of("status", "FAILED"));
        }

        log.info("✅ LAB TEST EXECUTION FINISHED - Success: {}", success);
    }
}