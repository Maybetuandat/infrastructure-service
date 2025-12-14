package com.example.infrastructure_service.service;

import io.kubernetes.client.openapi.models.V1Pod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.example.infrastructure_service.dto.InstanceTypeDTO;
import com.example.infrastructure_service.utils.PodLogWebSocketHandler;

@Service
@Slf4j
@RequiredArgsConstructor
public class VMTestOrchestrationService {

    private final KubernetesDiscoveryService discoveryService;
    private final VMTestLogStreamerService logStreamerService;
    private final VMService vmService;
    private final PodLogWebSocketHandler webSocketHandler;

    private void createTestVMResources(String testVmName, String namespace, InstanceTypeDTO instanceType) throws Exception {
        log.info("Creating test VM resources: {}", testVmName);
        vmService.ensureNamespaceExists(namespace);

        webSocketHandler.broadcastLogToPod(testVmName, "info",
                "✓ Namespace verified: " + namespace, null);

        webSocketHandler.broadcastLogToPod(testVmName, "info",
                "⏳ Creating PersistentVolumeClaim...", null);

        vmService.createPvc(
                testVmName,
                namespace,
                instanceType.getBackingImage(),
                instanceType.getStorageGb().toString()
        );

        webSocketHandler.broadcastLogToPod(testVmName, "success",
                "✅ PVC created successfully", null);

        webSocketHandler.broadcastLogToPod(testVmName, "info",
                "⏳ Creating VirtualMachine...", null);

        vmService.createVirtualMachine(testVmName, namespace,
                instanceType.getMemoryGb().toString(),
                instanceType.getCpuCores().toString());

        webSocketHandler.broadcastLogToPod(testVmName, "success",
                "✅ VirtualMachine created successfully", null);

        log.info("Test VM resources created successfully: {}", testVmName);
    }

    public boolean executeTestWorkflow(Integer labId, String labTitle, String testVmName, String namespace, int timeoutSeconds, InstanceTypeDTO instanceType) {
        boolean success = false;

        try {
            log.info("========================================");
            log.info(" PHASE 1: CREATING VM RESOURCES");
            log.info("========================================");

            webSocketHandler.broadcastLogToPod(testVmName, "info",
                    "🚀 Phase 1: Creating VM resources...", null);

            createTestVMResources(testVmName, namespace, instanceType);

            webSocketHandler.broadcastLogToPod(testVmName, "success",
                    "✅ VM resources created successfully", null);

            log.info("========================================");
            log.info(" PHASE 2: WAITING FOR VM POD");
            log.info("========================================");

            webSocketHandler.broadcastLogToPod(testVmName, "info",
                    "⏳ Phase 2: Waiting for virt-launcher pod...", null);

            V1Pod pod = discoveryService.waitForPodRunning(testVmName, namespace, 600);
            String podName = pod.getMetadata().getName();

            log.info("✅ Pod is running: {}", podName);
            webSocketHandler.broadcastLogToPod(testVmName, "success",
                    String.format("✅ Virt-launcher pod is running: %s", podName), null);

            log.info("Phase 3: Skipping external SSH check (Using Tunneling).");
            webSocketHandler.broadcastLogToPod(testVmName, "info",
                    "🔗 Phase 3: Establishing Secure Tunnel...", null);

            log.info("========================================");
            log.info(" PHASE 4: STREAMING CLOUD-INIT LOGS");
            log.info("========================================");

            webSocketHandler.broadcastLogToPod(testVmName, "info",
                    "📜 Phase 4: Checking cloud-init status via Tunnel...", null);

            logStreamerService.streamCloudInitLogs(namespace, podName, testVmName);

            webSocketHandler.broadcastLogToPod(testVmName, "success",
                    "✅ Cloud-init completed", null);

            success = true;

        } catch (Exception e) {
            log.error("❌ Error in test workflow: {}", e.getMessage(), e);
            webSocketHandler.broadcastLogToPod(testVmName, "error",
                    "❌ Test workflow error: " + e.getMessage(), null);
            success = false;

        } finally {
            log.info("========================================");
            log.info(" PHASE 6: CLEANUP");
            log.info("========================================");

            webSocketHandler.broadcastLogToPod(testVmName, "info",
                    "🧹 Phase 6: Cleaning up test resources...", null);

            try {
                deleteTestVMResources(testVmName, namespace);
                webSocketHandler.broadcastLogToPod(testVmName, "success",
                        "✅ Cleanup completed", null);
            } catch (Exception e) {
                log.error("❌ Error during cleanup: {}", e.getMessage(), e);
                webSocketHandler.broadcastLogToPod(testVmName, "warning",
                        "⚠️ Cleanup error: " + e.getMessage(), null);
            }
        }

        return success;
    }

    public void deleteTestVMResources(String testVmName, String namespace) {
        log.info("Deleting test VM resources: {}", testVmName);
        // vmService.deleteKubernetestVmObject(testVmName, namespace);
        // vmService.deleteKubernetesPvc(testVmName, namespace);
        log.info("Test VM resources deleted: {}", testVmName);
    }
}