package com.example.infrastructure_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ValidationRequest {
    private Integer labSessionId;
    private Integer questionId;
    private Integer userAnswerId;
    private String vmName;
    private String namespace;
    private String podName;
    private String validationCommand;
}