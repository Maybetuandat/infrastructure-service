package com.example.infrastructure_service.service;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim;
import io.kubernetes.client.util.Yaml;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;

import com.example.infrastructure_service.dto.LabTestRequest;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class VMService {
    
    private final CustomObjectsApi customApi;
    private final CoreV1Api coreApi;
    
    @Value("${KUBEVIRT_GROUP}")
    private String KUBEVIRT_GROUP;
    
    @Value("${KUBEVIRT_PLURAL_VM}")
    private String KUBEVIRT_PLURAL_VM;
    
    @Value("${KUBEVIRT_VERSION}")
    private String KUBEVIRT_VERSION;
    
    @Value("${CDI_PLURAL_DV}")
    private String CDI_PLURAL_DV;
    
    @Value("${CDI_GROUP}")
    private String CDI_GROUP;
    
    @Value("${CDI_VERSION}")
    private String CDI_VERSION;
    
    public void createKubernetesResourcesForTest(LabTestRequest request) throws IOException, ApiException {
        String vmName = request.getTestVmName();
        String namespace = request.getNamespace();
        
        ensureNamespaceExists(namespace);
        
        createPvc(
            vmName, 
            namespace, 
            request.getInstanceType().getBackingImage(),
            request.getInstanceType().getStorageGb().toString()
        );
        
        createVirtualMachine(
            vmName, 
            namespace, 
            request.getInstanceType().getMemoryGb().toString(),
            request.getInstanceType().getCpuCores().toString()
        );
    }
    
    public void ensureNamespaceExists(String namespace) throws ApiException {
        try {
            coreApi.readNamespace(namespace, null);
            log.info("Namespace '{}' already exists.", namespace);
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                log.info("Namespace '{}' not found. Creating...", namespace);
                V1Namespace namespaceBody = new V1Namespace()
                        .apiVersion("v1")
                        .kind("Namespace")
                        .metadata(new V1ObjectMeta().name(namespace));
                coreApi.createNamespace(namespaceBody, null, null, null, null);
                log.info("Namespace '{}' created successfully.", namespace);
            } else {
                log.error("Error checking namespace '{}'. Status code: {}. Response body: {}",
                        namespace, e.getCode(), e.getResponseBody());
                throw e;
            }
        }
    }
    
    public void createPvc(String vmName, String namespace, String backingImage, String storage) throws IOException, ApiException {
        Map<String, String> values = Map.of(
                "NAME", vmName,
                "NAMESPACE", namespace,
                "BACKING_IMAGE", backingImage,
                "STORAGE", storage
        );
        String pvcYaml = loadAndRenderTemplate("templates/pvc.yaml", values);
        V1PersistentVolumeClaim pvcBody = Yaml.loadAs(pvcYaml, V1PersistentVolumeClaim.class);

        log.info("Creating PersistentVolumeClaim '{}' using StorageClass 'longhorn-ext4-backing'...", vmName);

        try {
            coreApi.createNamespacedPersistentVolumeClaim(namespace, pvcBody, null, null, null, null);
            log.info("PersistentVolumeClaim '{}' created successfully.", vmName);
        } catch (ApiException e) {
            if (e.getCode() == 409) {
                log.info("PersistentVolumeClaim '{}' already exists.", vmName);
            } else {
                log.error("K8S API Exception when creating PVC. Status code: {}. Response body: {}", e.getCode(), e.getResponseBody());
                throw e;
            }
        }
    }
    
    private String loadAndRenderTemplate(String templatePath, Map<String, String> values) throws IOException {
        ClassPathResource resource = new ClassPathResource(templatePath);
        InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8);
        String template = FileCopyUtils.copyToString(reader);

        for (Map.Entry<String, String> entry : values.entrySet()) {
            template = template.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return template;
    }
    
    public void createVirtualMachine(String vmName, String namespace, String memory, String cpu) throws IOException, ApiException {
        Map<String, String> values = Map.of(
                "NAME", vmName,
                "NAMESPACE", namespace,
                "MEMORY", memory,
                "CPU", cpu
        );
        String virtualMachineYaml = loadAndRenderTemplate("templates/vm-template.yaml", values);
        @SuppressWarnings("rawtypes")
        Map vmBody = Yaml.loadAs(virtualMachineYaml, Map.class);

        log.info("Creating VirtualMachine '{}'...", vmName);

        try {
            customApi.createNamespacedCustomObject(KUBEVIRT_GROUP, KUBEVIRT_VERSION, namespace, KUBEVIRT_PLURAL_VM, vmBody, null, null, null);
            log.info("VirtualMachine '{}' definition created successfully.", vmName);
        } catch (ApiException e) {
            log.error("K8s error code: {}", e.getCode());
            log.error("K8s response body: {}", e.getResponseBody());
            log.error("Response headers: {}", e.getResponseHeaders());
            throw e;
        }
    }
}