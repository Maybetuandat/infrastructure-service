package com.example.infrastructure_service.service;

import com.example.infrastructure_service.dto.ExecuteCommandResult;
import com.example.infrastructure_service.dto.LabTestRequest;
import com.example.infrastructure_service.dto.UserLabSessionRequest;
import com.example.infrastructure_service.handler.AdminTestWebSocketHandler;
import com.example.infrastructure_service.socket.K8sTunnelSocketFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.*;
import io.kubernetes.client.openapi.ApiClient;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class SetupExecutionService {
    
    private final ObjectMapper objectMapper;
    private final ApiClient apiClient;
    
    private final AdminTestWebSocketHandler adminTestWebSocketHandler;
    private static final Logger executionLogger = LoggerFactory.getLogger("executionLogger");
    
     @Value("${ssh.default.username}")
    private String defaultUsername;

    @Value("${ssh.default.password}")
    private String defaultPassword;
    
    public SetupExecutionService(
            ObjectMapper objectMapper,
            @Qualifier("longTimeoutApiClient") ApiClient apiClient, 
            AdminTestWebSocketHandler adminTestWebSocketHandler) {
        this.objectMapper = objectMapper;
        this.apiClient = apiClient;
        this.apiClient.setReadTimeout(0);
        this.adminTestWebSocketHandler = adminTestWebSocketHandler;
    }
    
    public void executeSetupStepsForTest(LabTestRequest request, String podName) throws Exception {
        log.info("Starting setup steps execution for lab test: {} via K8s SocketFactory", request.getTestVmName());
        
        String vmName = request.getTestVmName();
        JSch jsch = new JSch();
        Session sshSession = null;
        
        try {
            List<Map<String, Object>> setupSteps = objectMapper.readValue(
                request.getSetupStepsJson(), 
                new TypeReference<List<Map<String, Object>>>() {}
            );
            
            if (setupSteps.isEmpty()) {
                log.info("No setup steps to execute for test VM {}", request.getTestVmName());
                   adminTestWebSocketHandler.broadcastLog(vmName, "info", "No setup steps to execute", null);
                return;
            }
            
            setupSteps = setupSteps.stream()
                .sorted(Comparator.comparing(step -> (Integer) step.get("stepOrder")))
                .collect(Collectors.toList());

            adminTestWebSocketHandler.broadcastLog(vmName, "info", 
                String.format("Found %d setup steps to execute", setupSteps.size()), null);
            sshSession = connectSshWithRetry(jsch, request.getNamespace(), podName, 20, 5000);
            adminTestWebSocketHandler.broadcastLog(vmName, "success", "SSH connected successfully", null);
            
            log.info("[Test VM {}] SSH connected via K8s Tunnel. Executing steps...", request.getTestVmName());
            int currentStep = 0;
            int totalSteps = setupSteps.size();
            for (Map<String, Object> step : setupSteps) {
                String title = (String) step.get("title");
                String command = (String) step.get("setupCommand");
                Integer expectedExitCode = (Integer) step.getOrDefault("expectedExitCode", 0);
                Integer timeoutSeconds = (Integer) step.getOrDefault("timeoutSeconds", 300);
                Boolean continueOnFailure = (Boolean) step.getOrDefault("continueOnFailure", false);
                
                log.info("[Test VM {}] Executing: {}", request.getTestVmName(), title);
                
                adminTestWebSocketHandler.broadcastLog(vmName, "step_start", 
                    String.format(" [%d/%d] Executing: %s", currentStep, totalSteps, title),
                    Map.of(
                        "stepNumber", currentStep,
                        "totalSteps", totalSteps,
                        "title", title,
                        "command", command
                    ));
                ExecuteCommandResult result = executeCommandOnSession(
                    sshSession, 
                    command, 
                    timeoutSeconds
                );
                
                
                logStepResult(request.getTestVmName(), title, result, expectedExitCode);
                
                boolean isSuccess = result.getExitCode() == expectedExitCode;
                 if (isSuccess) {
                     currentStep++; 
                    adminTestWebSocketHandler.broadcastLog(vmName, "step_success",
                        String.format("[%d/%d] Completed: %s", currentStep, totalSteps, title),
                        Map.of(
                            "stepNumber", currentStep,
                            "totalSteps", totalSteps,
                            "title", title,
                            "exitCode", result.getExitCode(),
                            "stdout", truncateOutput(result.getStdout(), 500)
                        ));
                } else {
                    adminTestWebSocketHandler.broadcastLog(vmName, "step_failed",
                        String.format(" [%d/%d] Failed: %s (exit code: %d)", 
                            currentStep, totalSteps, title, result.getExitCode()),
                        Map.of(
                            "stepNumber", currentStep,
                            "totalSteps", totalSteps,
                            "title", title,
                            "exitCode", result.getExitCode(),
                            "expectedExitCode", expectedExitCode,
                            "stdout", truncateOutput(result.getStdout(), 500),
                            "stderr", truncateOutput(result.getStderr(), 500)
                        ));
                    
                    if (!continueOnFailure) {
                            adminTestWebSocketHandler.broadcastLog(vmName, "error",
                            " Setup aborted due to step failure", null);
                        break;
                    } else {
                        adminTestWebSocketHandler.broadcastLog(vmName, "warning",
                            " Continuing despite failure (continueOnFailure=true)", null);
                    }
                }
                if (result.getExitCode() != expectedExitCode) {
                    if (!continueOnFailure) {
                        break;
                    }
                }
            }
            adminTestWebSocketHandler.broadcastLog(vmName, "setup_complete",
                String.format(" Setup completed: %d/%d steps executed", currentStep, totalSteps),
                Map.of("executedSteps", currentStep, "totalSteps", totalSteps));
        } catch (Exception e) {
            log.error("Setup failed for test VM {}: {}", request.getTestVmName(), e.getMessage(), e);
            throw e;
        } finally {
            if (sshSession != null && sshSession.isConnected()) {
                sshSession.disconnect();
            }
        }
    }
    
    public void executeSetupStepsForUserSession(UserLabSessionRequest request, String podName) throws Exception {
        log.info("Starting setup steps execution for user lab session: {} via K8s SocketFactory", request.getVmName());
        
        JSch jsch = new JSch();
        Session sshSession = null;
        
        try {
            List<Map<String, Object>> setupSteps = objectMapper.readValue(
                request.getSetupStepsJson(), 
                new TypeReference<List<Map<String, Object>>>() {}
            );
            
            if (setupSteps.isEmpty()) {
                log.info("No setup steps to execute for user session VM {}", request.getVmName());
                return;
            }
            
            setupSteps = setupSteps.stream()
                .sorted(Comparator.comparing(step -> (Integer) step.get("stepOrder")))
                .collect(Collectors.toList());
            
            sshSession = connectSshWithRetry(jsch, request.getNamespace(), podName, 20, 5000);
            
            log.info("[User Session VM {}] SSH connected via K8s Tunnel. Executing steps...", request.getVmName());
            
            for (Map<String, Object> step : setupSteps) {
                Integer stepOrder = (Integer) step.get("stepOrder");
                String description = (String) step.get("description");
                String commandScript = (String) step.get("commandScript");
                
                log.info("[User Session VM {}] Executing Step {}: {}", request.getVmName(), stepOrder, description);
                executionLogger.info("Executing Step {}: {}", stepOrder, description);
                
                ExecuteCommandResult result = executeCommandOnSession(sshSession, "echo \"\\hello world\" > hihi.txt", 300);
                
                log.info("[User Session VM {}] Step {} completed with exit code: {}", 
                    request.getVmName(), stepOrder, result.getExitCode());
                
                if (result.getExitCode() != 0) {
                    executionLogger.error("USER_SESSION_VM={}|STEP='{}'|FAILED|Code={}\nOUT: {}",
                        request.getVmName(), description, result.getExitCode(), result.getStdout());
                } else {
                    executionLogger.info("USER_SESSION_VM={}|STEP='{}'|SUCCESS", request.getVmName(), description);
                }
            }
            
            
            log.info("[User Session VM {}] All setup steps executed successfully.", request.getVmName());
            
        } catch (Exception e) {
            log.error("Setup failed for user session VM {}: {}", request.getVmName(), e.getMessage(), e);
            throw e;
        } finally {
            if (sshSession != null && sshSession.isConnected()) {
                sshSession.disconnect();
                log.info("[User Session VM {}] SSH session disconnected.", request.getVmName());
            }
        }
    }
    
    private Session connectSshWithRetry(JSch jsch, String namespace, String podName, 
                                       int maxRetries, long delayMs) throws Exception {
        for (int i = 0; i < maxRetries; i++) {
            try {
                Session session = jsch.getSession(defaultUsername, "localhost", 2222);
                session.setPassword(defaultPassword);
                session.setConfig("StrictHostKeyChecking", "no");
                
                session.setSocketFactory(new K8sTunnelSocketFactory(apiClient, namespace, podName));
                
                session.connect(15000);
                return session;
                
            } catch (JSchException e) {
                log.warn("SSH connect attempt {}/{} failed: {}. Retrying...", 
                    i + 1, maxRetries, e.getMessage());
                if (i == maxRetries - 1) throw e;
                Thread.sleep(delayMs);
            }
        }
        throw new RuntimeException("Failed to connect SSH after retries");
    }
    
    private ExecuteCommandResult executeCommandOnSession(Session session, String command, 
                                                         int timeoutSeconds) throws Exception {
        ChannelExec channel = null;
        StringBuilder outputBuffer = new StringBuilder();
        int exitCode = -1;
        
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            InputStream in = channel.getInputStream();
            InputStream err = channel.getErrStream();
            channel.connect(5000);
            
            byte[] buffer = new byte[1024];
            long startTime = System.currentTimeMillis();
            
            // do cơ chế của jsch là bất đồng bộ nên phải liên tục kiểm tra xem có dữ liệu trả về hay không 
            while (true) {
                while (in.available() > 0) {
                    int i = in.read(buffer, 0, 1024);
                    if (i < 0) break;
                    outputBuffer.append(new String(buffer, 0, i));
                }
                while (err.available() > 0) {
                    int i = err.read(buffer, 0, 1024);
                    if (i < 0) break;
                    outputBuffer.append(new String(buffer, 0, i));
                }
                if (channel.isClosed()) {
                    if (in.available() > 0) continue;
                    exitCode = channel.getExitStatus();
                    break;
                }
                if (timeoutSeconds > 0 && 
                    (System.currentTimeMillis() - startTime) > timeoutSeconds * 1000L) {
                    throw new IOException("Command timeout");
                }
                Thread.sleep(100);
            }
        } finally {
            if (channel != null) channel.disconnect();
        }
        
        return new ExecuteCommandResult(exitCode, outputBuffer.toString().trim(), "");
    }
    
    private void logStepResult(String testVmName, String stepTitle, 
                               ExecuteCommandResult result, Integer expectedExitCode) {
        if (result.getExitCode() == expectedExitCode) {
            executionLogger.info("TEST_VM={}|STEP='{}'|SUCCESS", testVmName, stepTitle);
        } else {
            executionLogger.error("TEST_VM={}|STEP='{}'|FAILED|Code={}\nOUT: {}\nERR: {}",
                testVmName, stepTitle, result.getExitCode(), result.getStdout(), result.getStderr());
        }
    }
    
    
    
   
     private String truncateOutput(String output, int maxLength) {
        if (output == null) return "";
        if (output.length() <= maxLength) return output;
        return output.substring(0, maxLength) + "... [truncated]";
    }
}