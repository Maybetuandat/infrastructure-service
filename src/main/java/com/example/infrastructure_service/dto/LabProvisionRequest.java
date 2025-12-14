package com.example.infrastructure_service.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LabProvisionRequest {
    private Integer sessionId;
    private Integer labId;
    private Integer userId;
    private String vmName;
    private String namespace;
    private String baseImage;
    private Integer cpuCores;
    private Integer memoryGb;
    private Integer storageGb;
    private String setupStepsJson;
}

