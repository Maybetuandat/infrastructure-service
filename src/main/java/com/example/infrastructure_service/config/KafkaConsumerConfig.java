package com.example.infrastructure_service.config;

import com.example.infrastructure_service.dto.ValidationRequest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import com.example.infrastructure_service.dto.LabSessionCleanupRequest;
import com.example.infrastructure_service.dto.LabTestRequest;
import com.example.infrastructure_service.dto.UserLabSessionRequest;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    private String groupId = "infrastructure-service";

    private Map<String, Object> getCommonConsumerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return props;
    }

    @Bean
    public ConsumerFactory<String, LabTestRequest> labTestConsumerFactory() {
        Map<String, Object> props = getCommonConsumerProps();
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, LabTestRequest.class.getName());
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, LabTestRequest> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, LabTestRequest> factory = 
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(labTestConsumerFactory());
        return factory;
    }

    @Bean
    public ConsumerFactory<String, UserLabSessionRequest> userLabSessionConsumerFactory() {
        Map<String, Object> props = getCommonConsumerProps();
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, UserLabSessionRequest.class.getName());
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, UserLabSessionRequest> userLabSessionKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, UserLabSessionRequest> factory = 
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(userLabSessionConsumerFactory());
        return factory;
    }
    
    @Bean
    public ConsumerFactory<String, ValidationRequest> validationConsumerFactory() {
        Map<String, Object> props = getCommonConsumerProps();
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, ValidationRequest.class.getName());
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ValidationRequest> validationKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, ValidationRequest> factory = 
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(validationConsumerFactory());
        return factory;
    }

    @Bean
    public ConsumerFactory<String, LabSessionCleanupRequest> cleanupConsumerFactory() {
        Map<String, Object> props = getCommonConsumerProps();
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, LabSessionCleanupRequest.class.getName());
        return new DefaultKafkaConsumerFactory<>(props);
    }
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, LabSessionCleanupRequest> cleanupKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, LabSessionCleanupRequest> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cleanupConsumerFactory());
        return factory;
    }
}