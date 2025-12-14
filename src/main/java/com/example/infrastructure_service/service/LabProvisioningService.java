package com.example.infrastructure_service.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.example.infrastructure_service.dto.LabProvisionRequest;
import com.example.infrastructure_service.dto.LabProvisionResponse;
import com.example.infrastructure_service.kafka.ProvisionResponseProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class LabProvisioningService {
    
    private final VMService vmService;
    private final KubernetesDiscoveryService discoveryService;
    private final SetupExecutionService setupExecutionService;
    private final ProvisionResponseProducer responseProducer;
    private final ObjectMapper objectMapper;
    
    @Async
    public void provisionLabAsync(LabProvisionRequest request) {
        String vmName = request.getVmName();
        String namespace = request.getNamespace();
        
        try {
            log.info("Phase 1: Creating VM resources for session {}...", request.getSessionId());
            sendStatusUpdate(request.getSessionId(), "PENDING", null, null);
            
            vmService.createKubernetesResources(request);
            
            log.info("Phase 2: Waiting for VM to be ready...");
            sendStatusUpdate(request.getSessionId(), "STARTING", null, null);
            
            var pod = discoveryService.waitForPodRunning(vmName, namespace, 1200);
            String podName = pod.getMetadata().getName();
            
            log.info("Phase 3: VM Pod is running: {}", podName);
            
            if (request.getSetupStepsJson() != null && !request.getSetupStepsJson().isEmpty()) {
                log.info("Phase 4: Executing setup steps...");
                sendStatusUpdate(request.getSessionId(), "SETTING_UP", podName, null);
                
                setupExecutionService.executeSetupSteps(request, podName);
                
                sendStatusUpdate(request.getSessionId(), "READY", podName, "Setup completed successfully");
            } else {
                sendStatusUpdate(request.getSessionId(), "READY", podName, "No setup steps required");
            }
            
        } catch (Exception e) {
            log.error("Error during lab provisioning: {}", e.getMessage(), e);
            sendStatusUpdate(request.getSessionId(), "SETUP_FAILED", null, e.getMessage());
        }
    }
    
    private void sendStatusUpdate(Integer sessionId, String status, String podName, String message) {
        LabProvisionResponse response = new LabProvisionResponse(
            sessionId, status, message, podName, null
        );
        responseProducer.sendProvisionResponse(response);
    }
}