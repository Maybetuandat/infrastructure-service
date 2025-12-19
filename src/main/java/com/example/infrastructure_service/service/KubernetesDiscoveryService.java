package com.example.infrastructure_service.service;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

@Service
@Slf4j
@RequiredArgsConstructor
public class KubernetesDiscoveryService {

    private final CoreV1Api coreApi;

    public V1Pod waitForPodRunning(String vmName, String namespace, int timeoutSeconds) throws ApiException, InterruptedException {
        long startTime = System.currentTimeMillis();
        String labelSelector = "app=" + vmName;

        while (System.currentTimeMillis() - startTime < timeoutSeconds * 1000L) {
            V1PodList podList = coreApi.listNamespacedPod(namespace, null, null, null, null, labelSelector, 1, null, null, null, null);
            if (podList.getItems().isEmpty()) {
                log.info("Waiting for pod with label '{}' to be created...", labelSelector);
                Thread.sleep(5000);
                continue;
            }

            V1Pod pod = podList.getItems().get(0);
            String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown";

            if ("Running".equals(phase)) {
                log.info("Pod '{}' is now Running.", pod.getMetadata().getName());
                return pod;
            }

            log.info("Pod '{}' is in phase '{}'. Waiting...", pod.getMetadata().getName(), phase);
            Thread.sleep(5000);
        }

        throw new RuntimeException("Timeout: Pod with label '" + labelSelector + "' did not enter Running state within " + timeoutSeconds + " seconds.");
    }

    public void waitForSshReady(String host, int port, int timeoutSeconds) throws InterruptedException {
        log.info("Waiting for SSH service to be ready at {}:{}...", host, port);
        long startTime = System.currentTimeMillis();
        boolean isSshReady = false;

        while (System.currentTimeMillis() - startTime < timeoutSeconds * 1000L) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 2000);
                isSshReady = true;
                log.info("SSH service is ready at {}:{}!", host, port);
                break;
            } catch (IOException e) {
                log.debug("SSH connection failed at {}:{}. Reason: {}", host, port, e.getMessage());
                Thread.sleep(5000);
            }
        }

        if (!isSshReady) {
            throw new RuntimeException("Timeout: SSH service did not become ready within " + timeoutSeconds + " seconds.");
        }
    }
}