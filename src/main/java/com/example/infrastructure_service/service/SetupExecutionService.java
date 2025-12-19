package com.example.infrastructure_service.service;

import com.example.infrastructure_service.dto.LabTestRequest;
import com.example.infrastructure_service.dto.UserLabSessionRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.*;
import io.kubernetes.client.PortForward;
import io.kubernetes.client.openapi.ApiClient;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class SetupExecutionService {
    
    private final ObjectMapper objectMapper;
    private final ApiClient apiClient;
    
    private static final Logger executionLogger = LoggerFactory.getLogger("executionLogger");
    
    private final String defaultUsername = "ubuntu";
    private final String defaultPassword = "1234";
    
    public SetupExecutionService(
            ObjectMapper objectMapper,
            @Qualifier("longTimeoutApiClient") ApiClient apiClient) {
        this.objectMapper = objectMapper;
        this.apiClient = apiClient;
        this.apiClient.setReadTimeout(0);
    }
    
    public void executeSetupStepsForTest(LabTestRequest request, String podName) throws Exception {
        log.info("Starting setup steps execution for lab test: {} via K8s SocketFactory", request.getTestVmName());
        
        JSch jsch = new JSch();
        Session sshSession = null;
        
        try {
            List<Map<String, Object>> setupSteps = objectMapper.readValue(
                request.getSetupStepsJson(), 
                new TypeReference<List<Map<String, Object>>>() {}
            );
            
            if (setupSteps.isEmpty()) {
                log.info("No setup steps to execute for test VM {}", request.getTestVmName());
                return;
            }
            
            setupSteps = setupSteps.stream()
                .sorted(Comparator.comparing(step -> (Integer) step.get("stepOrder")))
                .collect(Collectors.toList());
            
            sshSession = connectSshWithRetry(jsch, request.getNamespace(), podName, 20, 5000);
            
            log.info("[Test VM {}] SSH connected via K8s Tunnel. Executing steps...", request.getTestVmName());
            
            for (Map<String, Object> step : setupSteps) {
                String title = (String) step.get("title");
                String command = (String) step.get("setupCommand");
                Integer expectedExitCode = (Integer) step.getOrDefault("expectedExitCode", 0);
                Integer timeoutSeconds = (Integer) step.getOrDefault("timeoutSeconds", 300);
                Boolean continueOnFailure = (Boolean) step.getOrDefault("continueOnFailure", false);
                
                log.info("[Test VM {}] Executing: {}", request.getTestVmName(), title);
                
                ExecuteCommandResult result = executeCommandOnSession(
                    sshSession, 
                    command, 
                    timeoutSeconds
                );
                
                logStepResult(request.getTestVmName(), title, result, expectedExitCode);
                
                if (result.getExitCode() != expectedExitCode) {
                    if (!continueOnFailure) {
                        break;
                    }
                }
            }
            
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
                
                ExecuteCommandResult result = executeCommandOnSession(sshSession, commandScript, 300);
                
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
    
    public static class K8sTunnelSocketFactory implements SocketFactory {
        private final ApiClient apiClient;
        private final String namespace;
        private final String podName;
        
        public K8sTunnelSocketFactory(ApiClient apiClient, String namespace, String podName) {
            this.apiClient = apiClient;
            this.namespace = namespace;
            this.podName = podName;
        }
        
        @Override
        public Socket createSocket(String host, int port) throws IOException {
            try {
                PortForward forward = new PortForward(apiClient);
                PortForward.PortForwardResult result = forward.forward(
                    namespace, podName, Collections.singletonList(22)
                );
                
                return new VirtualSocket(result.getInputStream(22), result.getOutboundStream(22));
            } catch (Exception e) {
                throw new IOException("Failed to create K8s tunnel: " + e.getMessage(), e);
            }
        }
        
        @Override
        public InputStream getInputStream(Socket socket) throws IOException {
            return socket.getInputStream();
        }
        
        @Override
        public OutputStream getOutputStream(Socket socket) throws IOException {
            return socket.getOutputStream();
        }
    }
    
    public static class VirtualSocket extends Socket {
        private final InputStream in;
        private final OutputStream out;
        
        public VirtualSocket(InputStream in, OutputStream out) {
            this.in = in;
            this.out = out;
        }
        
        @Override
        public InputStream getInputStream() {
            return in;
        }
        
        @Override
        public OutputStream getOutputStream() {
            return out;
        }
        
        @Override
        public boolean isConnected() {
            return true;
        }
        
        @Override
        public void close() throws IOException {
            if(in != null) in.close();
            if(out != null) out.close();
        }
    }
    
    private static class ExecuteCommandResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;
        
        public ExecuteCommandResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
        
        public int getExitCode() { return exitCode; }
        public String getStdout() { return stdout; }
        public String getStderr() { return stderr; }
    }
}