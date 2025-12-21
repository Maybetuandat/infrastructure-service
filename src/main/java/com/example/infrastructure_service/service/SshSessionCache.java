package com.example.infrastructure_service.service;

import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class SshSessionCache {
    
    private final Map<String, Session> sessionCache = new ConcurrentHashMap<>();
    
    public void put(String key, Session session) {
        // Cleanup old session if exists
        Session oldSession = sessionCache.get(key);
        if (oldSession != null && oldSession.isConnected()) {
            try {
                oldSession.disconnect();
                log.debug("🔌 Disconnected old SSH session for key: {}", key);
            } catch (Exception e) {
                log.debug("Error disconnecting old session: {}", e.getMessage());
            }
        }
        
        sessionCache.put(key, session);
        log.info("💾 Cached SSH session for key: {}", key);
    }
    
    public Session get(String key) {
        Session session = sessionCache.get(key);
        
        if (session != null && !session.isConnected()) {
            log.warn("⚠️ Cached session for key {} is disconnected, removing", key);
            sessionCache.remove(key);
            return null;
        }
        
        return session;
    }
    
    public void remove(String key) {
        Session session = sessionCache.remove(key);
        if (session != null && session.isConnected()) {
            try {
                session.disconnect();
                log.info("🔌 Disconnected and removed SSH session for key: {}", key);
            } catch (Exception e) {
                log.debug("Error disconnecting session: {}", e.getMessage());
            }
        }
    }
    
    public void cleanup(String key) {
        remove(key);
    }
}