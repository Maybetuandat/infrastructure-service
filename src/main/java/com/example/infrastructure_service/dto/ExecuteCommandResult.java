package com.example.infrastructure_service.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ExecuteCommandResult {
    private  int exitCode;
    private  String stdout;
    private  String stderr;   
     private String truncateOutput(String output, int maxLength) {
        if (output == null) return "";
        if (output.length() <= maxLength) return output;
        return output.substring(0, maxLength) + "... [truncated]";
    }
}
