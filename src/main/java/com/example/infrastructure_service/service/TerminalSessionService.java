package com.example.infrastructure_service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class TerminalSessionService {

    // Cache mapping labSessionId -> {vmName, namespace}
    private final Map<Integer, Map<String, String>> sessionCache = new ConcurrentHashMap<>();

    /**
     * Register terminal session after VM is created and ready
     */
    public void registerSession(Integer labSessionId, String vmName, String namespace) {
        Map<String, String> sessionInfo = new HashMap<>();
        sessionInfo.put("vmName", vmName);
        sessionInfo.put("namespace", namespace);
        
        sessionCache.put(labSessionId, sessionInfo);
        
        log.info("✅ Terminal session registered: labSessionId={} -> VM={}, namespace={}", 
            labSessionId, vmName, namespace);
    }

    /**
     * Get terminal session info
     */
    public Map<String, String> getSession(Integer labSessionId) {
        Map<String, String> sessionInfo = sessionCache.get(labSessionId);
        
        if (sessionInfo == null) {
            log.warn("⚠️ Terminal session not found for labSessionId: {}", labSessionId);
        }
        
        return sessionInfo;
    }

    /**
     * Remove terminal session (cleanup after disconnect)
     */
    public void removeSession(Integer labSessionId) {
        Map<String, String> removed = sessionCache.remove(labSessionId);
        
        if (removed != null) {
            log.info("🧹 Terminal session removed: labSessionId={} -> VM={}", 
                labSessionId, removed.get("vmName"));
        }
    }

    /**
     * Check if terminal session exists
     */
    public boolean exists(Integer labSessionId) {
        return sessionCache.containsKey(labSessionId);
    }
}