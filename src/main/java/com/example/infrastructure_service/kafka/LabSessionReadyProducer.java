package com.example.infrastructure_service.kafka;

import com.example.infrastructure_service.dto.LabSessionReadyEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class LabSessionReadyProducer {

    private final KafkaTemplate<String, LabSessionReadyEvent> labSessionReadyKafkaTemplate;
    private static final String TOPIC = "lab-session-ready";

    public void sendLabSessionReady(LabSessionReadyEvent event) {
        log.info("Sending lab session ready event to Kafka: labSessionId={}, vmName={}", 
            event.getLabSessionId(), event.getVmName());
        labSessionReadyKafkaTemplate.send(TOPIC, String.valueOf(event.getLabSessionId()), event);
    }
}