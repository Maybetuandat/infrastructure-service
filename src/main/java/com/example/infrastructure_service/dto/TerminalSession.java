package com.example.infrastructure_service.dto;

import java.io.OutputStream;
import java.time.LocalDateTime;

import com.jcraft.jsch.ChannelShell;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TerminalSession {
    private String podName;
    private int labSessionId;
    private boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime lastActivityAt;
    
    // SSH resources
    private ChannelShell sshChannel;
    private OutputStream sshOutputStream;
    private Thread outputReader;
    
    public void updateActivity() {
        this.lastActivityAt = LocalDateTime.now();
    }
}