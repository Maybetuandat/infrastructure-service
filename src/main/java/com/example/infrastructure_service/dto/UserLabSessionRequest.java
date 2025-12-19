package com.example.infrastructure_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserLabSessionRequest {
    private Integer labSessionId;
    private String vmName;
    private String namespace;
    private Integer labId;
    private InstanceTypeDTO instanceType;
    private String setupStepsJson;
}