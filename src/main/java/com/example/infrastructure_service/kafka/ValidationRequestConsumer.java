package com.example.infrastructure_service.kafka;

import com.example.infrastructure_service.dto.ValidationRequest;
import com.example.infrastructure_service.service.ValidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ValidationRequestConsumer {
    
    private final ValidationService validationService;
    
    @KafkaListener(
        topics = "lab-validation-requests", 
        groupId = "infrastructure-service",
        containerFactory = "validationKafkaListenerContainerFactory"
    )
    public void consumeValidationRequest(ValidationRequest request) {
        log.info("📥 Received validation request: labSessionId={}, questionId={}", 
            request.getLabSessionId(), request.getQuestionId());
        
        try {
            validationService.handleValidationRequest(request);
        } catch (Exception e) {
            log.error("❌ Failed to process validation request: {}", e.getMessage(), e);
        }
    }
}