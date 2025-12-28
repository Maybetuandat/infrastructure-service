package com.example.infrastructure_service.service;

import com.example.infrastructure_service.dto.ValidationRequest;
import com.example.infrastructure_service.dto.ValidationResponse;
import com.example.infrastructure_service.kafka.ValidationResponseProducer;
import com.example.infrastructure_service.service.SetupExecutionService.K8sTunnelSocketFactory;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import io.kubernetes.client.openapi.ApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.InputStream;

@Service
@Slf4j
@RequiredArgsConstructor
public class ValidationService {
    
    private final ValidationResponseProducer validationResponseProducer;
    
    @Qualifier("longTimeoutApiClient")
    private final ApiClient apiClient;
    
    private final String defaultUsername = "ubuntu";
    private final String defaultPassword = "ubuntu";
    
    public void handleValidationRequest(ValidationRequest request) {
        log.info("🔧 Processing validation request for labSessionId={}, questionId={}", 
            request.getLabSessionId(), request.getQuestionId());
        
        JSch jsch = new JSch();
        Session sshSession = null;
        
        try {
            // Connect SSH to pod
            sshSession = connectSsh(jsch, request.getNamespace(), request.getPodName());
            
            // Execute validation command
            ExecuteCommandResult result = executeCommand(sshSession, request.getValidationCommand(), 30);
            
            // Determine if correct based on exit code
            boolean isCorrect = (result.getExitCode() == 0);
            
            log.info("✅ Validation completed: labSessionId={}, questionId={}, isCorrect={}, exitCode={}", 
                request.getLabSessionId(), request.getQuestionId(), isCorrect, result.getExitCode());
            
            // Send response back to CMS
            ValidationResponse response = ValidationResponse.builder()
                .labSessionId(request.getLabSessionId())
                .questionId(request.getQuestionId())
                .isCorrect(isCorrect)
                .output(result.getStdout())
                .error(result.getStderr())
                .build();
            
            validationResponseProducer.sendValidationResponse(response);
            
        } catch (Exception e) {
            log.error("❌ Validation failed: labSessionId={}, questionId={}, error={}", 
                request.getLabSessionId(), request.getQuestionId(), e.getMessage(), e);
            
            // Send failure response
            ValidationResponse errorResponse = ValidationResponse.builder()
                .labSessionId(request.getLabSessionId())
                .questionId(request.getQuestionId())
                .isCorrect(false)
                .output("")
                .error("Validation error: " + e.getMessage())
                .build();
            
            validationResponseProducer.sendValidationResponse(errorResponse);
            
        } finally {
            if (sshSession != null && sshSession.isConnected()) {
                sshSession.disconnect();
                log.debug("SSH session disconnected for validation");
            }
        }
    }
    
    private Session connectSsh(JSch jsch, String namespace, String podName) throws Exception {
        Session session = jsch.getSession(defaultUsername, "localhost", 2222);
        session.setPassword(defaultPassword);
        session.setConfig("StrictHostKeyChecking", "no");
        session.setSocketFactory(new K8sTunnelSocketFactory(apiClient, namespace, podName));
        session.connect(15000);
        return session;
    }
    
    private ExecuteCommandResult executeCommand(Session session, String command, int timeoutSeconds) throws Exception {
        ChannelExec channel = null;
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        int exitCode = -1;
        
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            
            InputStream in = channel.getInputStream();
            InputStream err = channel.getErrStream();
            channel.connect(5000);
            
            byte[] buffer = new byte[1024];
            long startTime = System.currentTimeMillis();
            
            while (true) {
                while (in.available() > 0) {
                    int i = in.read(buffer, 0, 1024);
                    if (i < 0) break;
                    stdout.append(new String(buffer, 0, i));
                }
                
                while (err.available() > 0) {
                    int i = err.read(buffer, 0, 1024);
                    if (i < 0) break;
                    stderr.append(new String(buffer, 0, i));
                }
                
                if (channel.isClosed()) {
                    if (in.available() > 0) continue;
                    exitCode = channel.getExitStatus();
                    break;
                }
                
                if (timeoutSeconds > 0 && (System.currentTimeMillis() - startTime) > timeoutSeconds * 1000L) {
                    log.warn("Command execution timeout after {} seconds", timeoutSeconds);
                    break;
                }
                
                Thread.sleep(100);
            }
            
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
        
        return new ExecuteCommandResult(exitCode, stdout.toString(), stderr.toString());
    }
    
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class ExecuteCommandResult {
        private int exitCode;
        private String stdout;
        private String stderr;
    }
}