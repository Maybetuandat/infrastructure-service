package com.example.infrastructure_service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LabTestRequest {
    private Integer labId;
    private String testVmName;
    private String namespace;
    private String labTitle;
    private InstanceTypeDTO instanceType;
    private String setupStepsJson;
}