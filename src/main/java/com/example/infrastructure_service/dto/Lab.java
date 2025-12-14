package com.example.infrastructure_service.dto;
import lombok.Data;


@Data
public class Lab {
    private String title;
    private String description;
    private String namespace;
    private Integer estimatedTime;
    private Boolean isActive;
}
