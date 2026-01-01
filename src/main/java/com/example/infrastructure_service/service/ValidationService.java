package com.example.infrastructure_service.service;

import com.example.infrastructure_service.dto.ValidationRequest;
import com.example.infrastructure_service.dto.ValidationResponse;
import com.example.infrastructure_service.kafka.ValidationResponseProducer;
import com.example.infrastructure_service.service.SetupExecutionService.K8sTunnelSocketFactory;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Map;

@Service
@Slf4j
public class ValidationService {
    
    private final ValidationResponseProducer validationResponseProducer;
    private final CoreV1Api coreApi;
    private final ApiClient apiClient;
    private final TerminalSessionService terminalSessionService;
    
    // Sử dụng config thay vì hardcode
    @Value("${ssh.default.username:ubuntu}")
    private String defaultUsername;
    
    @Value("${ssh.default.password:ubuntu}")
    private String defaultPassword;
    
    // Retry configuration
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000;
    
    public ValidationService(
            ValidationResponseProducer validationResponseProducer,
            CoreV1Api coreApi,
            @Qualifier("longTimeoutApiClient") ApiClient apiClient,
            TerminalSessionService terminalSessionService) {
        this.validationResponseProducer = validationResponseProducer;
        this.coreApi = coreApi;
        this.apiClient = apiClient;
        this.terminalSessionService = terminalSessionService;
    }
    
    public void handleValidationRequest(ValidationRequest request) {
        log.info("🔧 Processing validation request: labSessionId={}, questionId={}, vmName={}, namespace={}, podName={}", 
            request.getLabSessionId(), request.getQuestionId(), request.getVmName(), 
            request.getNamespace(), request.getPodName());
        
        JSch jsch = new JSch();
        Session sshSession = null;
        
        try {
            // Bước 1: Thử lấy podName từ TerminalSessionService cache trước (đây là source chính xác nhất)
            String actualPodName = null;
            
            Map<String, String> cachedSession = terminalSessionService.getSession(request.getLabSessionId());
            if (cachedSession != null && cachedSession.get("podName") != null) {
                actualPodName = cachedSession.get("podName");
                log.info("📍 Found cached pod name from TerminalSessionService: {}", actualPodName);
            }
            
            // Bước 2: Nếu không có trong cache, resolve từ K8s API
            if (actualPodName == null) {
                log.info("🔍 Pod name not in cache, resolving from K8s API...");
                actualPodName = resolveActualPodName(request.getNamespace(), request.getVmName());
            }
            
            if (actualPodName == null) {
                throw new RuntimeException("Pod not found for vmName: " + request.getVmName() + 
                    " in namespace: " + request.getNamespace() + 
                    ". The VM may have been terminated or not yet created.");
            }
            
            log.info("📍 Using pod name: {} (vmName: {})", actualPodName, request.getVmName());
            
            // Bước 3: Connect SSH với retry
            sshSession = connectSshWithRetry(jsch, request.getNamespace(), actualPodName);
            
            // Bước 3: Execute validation command
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
    
    /**
     * Resolve actual pod name from vmName by querying Kubernetes API.
     * In KubeVirt, the actual pod name is usually "virt-launcher-{vmName}-{random}"
     * 
     * @param namespace The namespace to search in
     * @param vmName The VM name (e.g., "vm-15")
     * @return The actual pod name or null if not found
     */
    private String resolveActualPodName(String namespace, String vmName) {
        try {
            log.info("🔍 Resolving pod name for vmName={} in namespace={}", vmName, namespace);
            
            // Try different label selectors that KubeVirt uses
            String[] labelSelectors = {
                "kubevirt.io/vm=" + vmName,               // KubeVirt VM label (from vm-template.yaml)
                "app=" + vmName,                          // Generic app label (from vm-template.yaml)
                "vm.kubevirt.io/name=" + vmName           // Alternative KubeVirt label
            };
            
            for (String labelSelector : labelSelectors) {
                log.debug("🔍 Trying labelSelector: {}", labelSelector);
                
                V1PodList podList = coreApi.listNamespacedPod(
                    namespace, 
                    null,    // pretty
                    null,    // allowWatchBookmarks
                    null,    // _continue
                    null,    // fieldSelector
                    labelSelector, 
                    10,      // limit
                    null,    // resourceVersion
                    null,    // resourceVersionMatch
                    null,    // sendInitialEvents
                    null     // timeoutSeconds
                );
                
                log.debug("🔍 Found {} pods with labelSelector: {}", podList.getItems().size(), labelSelector);
                
                for (V1Pod pod : podList.getItems()) {
                    String podName = pod.getMetadata().getName();
                    String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown";
                    
                    log.info("📦 Found pod: {} (phase: {})", podName, phase);
                    
                    // Chỉ return nếu pod đang Running
                    if ("Running".equals(phase)) {
                        log.info("✅ Using running pod: {}", podName);
                        return podName;
                    }
                }
            }
            
            // Fallback: List all pods and search by name prefix
            log.info("🔍 Fallback: Listing all pods in namespace {} to find virt-launcher-{}...", namespace, vmName);
            V1PodList allPods = coreApi.listNamespacedPod(
                namespace, null, null, null, null, null, null, null, null, null, null
            );
            
            log.info("📦 Total pods in namespace {}: {}", namespace, allPods.getItems().size());
            
            String prefix = "virt-launcher-" + vmName + "-";
            for (V1Pod pod : allPods.getItems()) {
                String podName = pod.getMetadata().getName();
                String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown";
                
                log.debug("📦 Checking pod: {} (phase: {})", podName, phase);
                
                if (podName.startsWith(prefix) && "Running".equals(phase)) {
                    log.info("✅ Found pod by prefix: {}", podName);
                    return podName;
                }
            }
            
            // Log all pods for debugging
            log.warn("❌ No running pod found for vmName: {} in namespace: {}", vmName, namespace);
            log.warn("📋 All pods in namespace {}:", namespace);
            for (V1Pod pod : allPods.getItems()) {
                log.warn("   - {} (phase: {})", 
                    pod.getMetadata().getName(), 
                    pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown");
            }
            
            return null;
            
        } catch (ApiException e) {
            log.error("❌ Kubernetes API error while resolving pod name: {} (code: {}, body: {})", 
                e.getMessage(), e.getCode(), e.getResponseBody());
            return null;
        }
    }
    
    /**
     * Connect SSH with retry mechanism
     */
    private Session connectSshWithRetry(JSch jsch, String namespace, String podName) throws Exception {
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.info("🔄 SSH connection attempt {}/{} to pod: {}", attempt, MAX_RETRIES, podName);
                
                Session session = jsch.getSession(defaultUsername, "localhost", 2222);
                session.setPassword(defaultPassword);
                session.setConfig("StrictHostKeyChecking", "no");
                session.setSocketFactory(new K8sTunnelSocketFactory(apiClient, namespace, podName));
                session.connect(15000);
                
                log.info("✅ SSH connected successfully to pod: {}", podName);
                return session;
                
            } catch (JSchException e) {
                lastException = e;
                log.warn("⚠️ SSH connection attempt {}/{} failed: {}", attempt, MAX_RETRIES, e.getMessage());
                
                if (attempt < MAX_RETRIES) {
                    Thread.sleep(RETRY_DELAY_MS);
                }
            }
        }
        
        throw new RuntimeException("SSH connection failed after " + MAX_RETRIES + " attempts", lastException);
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
            long timeoutMs = timeoutSeconds * 1000L;
            
            while (!channel.isClosed()) {
                // Read stdout
                while (in.available() > 0) {
                    int read = in.read(buffer);
                    if (read > 0) {
                        stdout.append(new String(buffer, 0, read));
                    }
                }
                
                // Read stderr
                while (err.available() > 0) {
                    int read = err.read(buffer);
                    if (read > 0) {
                        stderr.append(new String(buffer, 0, read));
                    }
                }
                
                // Check timeout
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    throw new RuntimeException("Command execution timeout after " + timeoutSeconds + " seconds");
                }
                
                Thread.sleep(100);
            }
            
            // Read remaining output
            while (in.available() > 0) {
                int read = in.read(buffer);
                if (read > 0) {
                    stdout.append(new String(buffer, 0, read));
                }
            }
            while (err.available() > 0) {
                int read = err.read(buffer);
                if (read > 0) {
                    stderr.append(new String(buffer, 0, read));
                }
            }
            
            exitCode = channel.getExitStatus();
            
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
        
        return new ExecuteCommandResult(exitCode, stdout.toString().trim(), stderr.toString().trim());
    }
    
    // Inner class for command result
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