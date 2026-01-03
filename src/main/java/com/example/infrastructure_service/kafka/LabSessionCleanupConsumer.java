package com.example.infrastructure_service.kafka;

import com.example.infrastructure_service.dto.LabSessionCleanupRequest;
import com.example.infrastructure_service.service.ResourceCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class LabSessionCleanupConsumer {

    private final ResourceCleanupService resourceCleanupService;

    @KafkaListener(
            topics = "lab-session-cleanup-requests",
            groupId = "infrastructure-service",
            containerFactory = "cleanupKafkaListenerContainerFactory"
    )
    public void consumeCleanupRequest(LabSessionCleanupRequest request) {
        log.info(" Received cleanup request: labSessionId={}, vmName={}, namespace={}",
                request.getLabSessionId(), request.getVmName(), request.getNamespace());

        try {
            resourceCleanupService.handleCleanupRequest(request);
        } catch (Exception e) {
            log.error(" Failed to process cleanup request: {}", e.getMessage(), e);
        }
    }
}