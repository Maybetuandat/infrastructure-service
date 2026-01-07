package com.example.infrastructure_service.socket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Collections;

import com.jcraft.jsch.SocketFactory;

import io.kubernetes.client.PortForward;
import io.kubernetes.client.openapi.ApiClient;

public  class K8sTunnelSocketFactory implements SocketFactory {
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