package com.example.infrastructure_service.service;

import com.example.infrastructure_service.dto.LabSessionCleanupRequest;
import com.example.infrastructure_service.handler.PodLogWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ResourceCleanupService {

    private final VMService vmService;
    private final TerminalSessionService terminalSessionService;
    private final SshSessionCache sshSessionCache;
    private final PodLogWebSocketHandler podLogWebSocketHandler;

    private static final long SSH_CLEANUP_DELAY_MS = 2000;
    private static final long K8S_RESOURCE_DELAY_MS = 3000;
    private static final long PVC_DELETE_DELAY_MS = 5000;

    @Async
    public void handleCleanupRequest(LabSessionCleanupRequest request) {
        log.info("Starting resource cleanup for labSessionId={}, vmName={}, namespace={}",
                request.getLabSessionId(), request.getVmName(), request.getNamespace());

        try {
            log.info("Step 1: Cleaning up terminal session...");
            cleanupTerminalSession(request);
            sleep(SSH_CLEANUP_DELAY_MS);
            log.info("Step 2: Cleaning up SSH session cache...");
            cleanupSshSession(request);
            sleep(K8S_RESOURCE_DELAY_MS);
            log.info("Step 3: Deleting Kubernetes resources...");
            deleteKubernetesResources(request);

            log.info("Resource cleanup completed successfully for labSessionId={}",
                    request.getLabSessionId());

        } catch (Exception e) {
            log.error("Error during resource cleanup for labSessionId={}: {}",
                    request.getLabSessionId(), e.getMessage(), e);
        }
    }

    private void cleanupTerminalSession(LabSessionCleanupRequest request) {
        try {
            podLogWebSocketHandler.cleanupTerminal(request.getVmName());
            terminalSessionService.removeSession(request.getLabSessionId());
            log.info("Terminal session cleaned up for vmName={}", request.getVmName());
        } catch (Exception e) {
            log.warn("Error cleaning up terminal session: {}", e.getMessage());
        }
    }

    private void cleanupSshSession(LabSessionCleanupRequest request) {
        try {
            String cacheKey = "lab-session-" + request.getLabSessionId();
            sshSessionCache.cleanup(cacheKey);
            log.info("SSH session cache cleaned up for key={}", cacheKey);
        } catch (Exception e) {
            log.warn("Error cleaning up SSH session cache: {}", e.getMessage());
        }
    }

    private void deleteKubernetesResources(LabSessionCleanupRequest request) {
        try {
            String vmName = request.getVmName();
            String namespace = request.getNamespace();

            log.info("Deleting VirtualMachine: {} in namespace: {}", vmName, namespace);
            vmService.deleteVirtualMachine(vmName, namespace);
            sleep(PVC_DELETE_DELAY_MS);

            log.info("Deleting PVC: {} in namespace: {}", vmName, namespace);
            vmService.deletePvc(vmName, namespace);

            log.info("Kubernetes resources deleted for vmName={}", vmName);
        } catch (Exception e) {
            log.error("Error deleting Kubernetes resources: {}", e.getMessage(), e);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Sleep interrupted during cleanup");
        }
    }
}