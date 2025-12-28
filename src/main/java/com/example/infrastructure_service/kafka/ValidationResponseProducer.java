package com.example.infrastructure_service.kafka;

import com.example.infrastructure_service.dto.ValidationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ValidationResponseProducer {
    
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    
    private static final String TOPIC = "lab-validation-responses";
    
    public void sendValidationResponse(ValidationResponse response) {
        try {
            String message = objectMapper.writeValueAsString(response);
            kafkaTemplate.send(TOPIC, message);
            log.info("📤 Sent validation response: labSessionId={}, questionId={}, isCorrect={}", 
                response.getLabSessionId(), response.getQuestionId(), response.isCorrect());
        } catch (Exception e) {
            log.error("❌ Failed to send validation response", e);
            throw new RuntimeException("Failed to send validation response", e);
        }
    }
}