package com.example.infrastructure_service.dto;
import lombok.Data;
@Data
public class InstanceType {   
    private String name;
    private String description;
    private Integer cpuCores;
    private Integer memoryGb;
    private Integer storageGb;
    private String backingImage;
}
