package com.example.infrastructure_service.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.example.infrastructure_service.dto.LabTestRequest;
import com.example.infrastructure_service.service.VMTestService;

@Service
@Slf4j
@RequiredArgsConstructor
public class LabTestRequestConsumer {
    
    private final VMTestService vmTestService;
    
    @KafkaListener(topics = "lab-test-requests", groupId = "infrastructure-service")
    public void consumeLabTestRequest(LabTestRequest request) {
        log.info("Received lab test request: testVmName={}", request.getTestVmName());
        
        try {
            vmTestService.handleLabTestRequest(request);
        } catch (Exception e) {
            log.error("Failed to process lab test request: {}", e.getMessage(), e);
        }
    }
}