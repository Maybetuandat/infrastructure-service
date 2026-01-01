
package com.example.infrastructure_service.config;

import com.example.infrastructure_service.handler.AdminTestWebSocketHandler;
import com.example.infrastructure_service.handler.PodLogWebSocketHandler;
import com.example.infrastructure_service.service.TerminalSessionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final AdminTestWebSocketHandler adminTestHandler;
    private final PodLogWebSocketHandler podLogHandler;
    private final TerminalSessionService terminalSessionService;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(adminTestHandler, "/ws/admin/test-lab")
                .setAllowedOrigins("*");
        registry.addHandler(podLogHandler, "/ws/pod-logs")
                .addInterceptors(new StudentLabSessionInterceptor())
                .setAllowedOrigins("*");
    }
    private static class StudentLabSessionInterceptor implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) throws Exception {
            if (request instanceof ServletServerHttpRequest) {
                ServletServerHttpRequest servletRequest = (ServletServerHttpRequest) request;
                
                String query = servletRequest.getURI().getQuery();
                
                // Extract token if present
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
                
                attributes.put("token", token);
                
                log.info("Student lab WebSocket handshake successful");
                return true;
            }
            
            log.error("Invalid request type");
            return false;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
            if (exception != null) {
                log.error("Student lab WebSocket handshake failed", exception);
            }
        }
    }
}