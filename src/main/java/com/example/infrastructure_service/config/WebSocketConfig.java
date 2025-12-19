// infrastructure-service/src/main/java/com/example/infrastructure_service/config/WebSocketConfig.java
package com.example.infrastructure_service.config;

import com.example.infrastructure_service.handler.TerminalHandler;
import com.example.infrastructure_service.service.TerminalSessionService;
import com.example.infrastructure_service.utils.PodLogWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
@Slf4j
public class WebSocketConfig implements WebSocketConfigurer {

    private final PodLogWebSocketHandler podLogHandler;
    private final TerminalHandler terminalHandler;
    private final TerminalSessionService terminalSessionService;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Phase 1: Pod logs WebSocket (for VM creation progress)
        registry.addHandler(podLogHandler, "/ws/pod-logs")
                .setAllowedOrigins("*");

        // Phase 2: Terminal WebSocket (for SSH interactive session)
        registry.addHandler(terminalHandler, "/ws/terminal/{labSessionId}")
                .addInterceptors(new TerminalSessionInterceptor(terminalSessionService))
                .setAllowedOrigins("*");
    }

    @RequiredArgsConstructor
    private static class TerminalSessionInterceptor implements HandshakeInterceptor {
        
        private final TerminalSessionService terminalSessionService;

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
            if (request instanceof ServletServerHttpRequest) {
                ServletServerHttpRequest servletRequest = (ServletServerHttpRequest) request;
                
                // Extract labSessionId from path
                String path = servletRequest.getURI().getPath();
                String labSessionIdStr = path.substring(path.lastIndexOf('/') + 1);
                
                try {
                    Integer labSessionId = Integer.parseInt(labSessionIdStr);
                    
                    // Get VM details from cache
                    var sessionInfo = terminalSessionService.getSession(labSessionId);
                    
                    if (sessionInfo == null) {
                        log.error("❌ Terminal session not found for labSessionId: {}. VM may not be ready yet.", labSessionId);
                        return false; // Reject connection
                    }
                    
                    // Extract token from query parameter (optional, for future authentication)
                    String query = servletRequest.getURI().getQuery();
                    String token = null;
                    if (query != null) {
                        String[] params = query.split("&");
                        for (String param : params) {
                            if (param.startsWith("token=")) {
                                token = java.net.URLDecoder.decode(param.substring(6), "UTF-8");
                                break;
                            }
                        }
                    }
                    
                    // Store session attributes
                    attributes.put("labSessionId", labSessionId);
                    attributes.put("vmName", sessionInfo.get("vmName"));
                    attributes.put("namespace", sessionInfo.get("namespace"));
                    attributes.put("token", token);
                    
                    log.info("✅ Terminal WebSocket handshake successful for labSessionId: {}, VM: {}", 
                        labSessionId, sessionInfo.get("vmName"));
                    
                    return true;
                    
                } catch (NumberFormatException e) {
                    log.error("❌ Invalid labSessionId format: {}", labSessionIdStr);
                    return false;
                }
            }
            
            log.error("❌ Invalid request type");
            return false;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
            if (exception != null) {
                log.error("❌ Terminal WebSocket handshake failed", exception);
            }
        }
    }
}