package com.example.infrastructure_service.kafka;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.example.infrastructure_service.dto.LabProvisionRequest;
import com.example.infrastructure_service.dto.LabProvisionResponse;
import com.example.infrastructure_service.service.LabProvisioningService;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProvisionRequestConsumer {
    
    private final LabProvisioningService provisioningService;
    private final ProvisionResponseProducer responseProducer;
    
    @KafkaListener(topics = "lab-provision-requests", groupId = "infrastructure-service")
    public void consumeProvisionRequest(LabProvisionRequest request) {
        log.info("Received provision request for session: {}", request.getSessionId());
        
        try {
            provisioningService.provisionLabAsync(request);
        } catch (Exception e) {
            log.error("Failed to process provision request: {}", e.getMessage(), e);
            LabProvisionResponse errorResponse = new LabProvisionResponse(
                request.getSessionId(),
                "SETUP_FAILED",
                "Failed to provision lab",
                null,
                e.getMessage()
            );
            responseProducer.sendProvisionResponse(errorResponse);
        }
    }
}