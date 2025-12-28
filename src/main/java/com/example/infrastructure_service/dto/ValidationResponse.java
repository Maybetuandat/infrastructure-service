package com.example.infrastructure_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ValidationResponse {
    private Integer labSessionId;
    private Integer questionId;
    private boolean isCorrect;
    private String output;
    private String error;
}