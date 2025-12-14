package com.example.infrastructure_service.kafka;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.example.infrastructure_service.dto.LabProvisionResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProvisionResponseProducer {
    
    private final KafkaTemplate<String, LabProvisionResponse> kafkaTemplate;
    private static final String TOPIC = "lab-provision-responses";
    
    public void sendProvisionResponse(LabProvisionResponse response) {
        log.info("Sending provision response for session: {} with status: {}", 
            response.getSessionId(), response.getStatus());
        kafkaTemplate.send(TOPIC, response.getSessionId().toString(), response);
    }
}