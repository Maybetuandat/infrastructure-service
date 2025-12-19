package com.example.infrastructure_service.dto;



import lombok.Data;

@Data
public class TerminalSessionRequest {
    private Integer labSessionId;
    private String vmName;
    private String namespace;
}