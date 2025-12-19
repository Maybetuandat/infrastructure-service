package com.example.infrastructure_service.kafka;



import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import com.example.infrastructure_service.dto.UserLabSessionRequest;
import com.example.infrastructure_service.service.VMUserSessionService;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserLabSessionConsumer {
    
    private final VMUserSessionService vmUserSessionService;
    
    @KafkaListener(topics = "user-lab-session-requests", groupId = "infrastructure-service", containerFactory = "userLabSessionKafkaListenerContainerFactory")
    public void consumeUserLabSessionRequest(UserLabSessionRequest request) {
        log.info("Received user lab session request: vmName={}, labSessionId={}", 
            request.getVmName(), request.getLabSessionId());
        
        try {
            vmUserSessionService.handleUserLabSessionRequest(request);
        } catch (Exception e) {
            log.error("Failed to process user lab session request: {}", e.getMessage(), e);
        }
    }
}
