package com.example.infrastructure_service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class TerminalSessionService {

    private final Map<Integer, Map<String, String>> sessionCache = new ConcurrentHashMap<>();

    public void registerSession(Integer labSessionId, String vmName, String namespace, String podName) {
        Map<String, String> sessionInfo = new HashMap<>();
        sessionInfo.put("vmName", vmName);
        sessionInfo.put("namespace", namespace);
        sessionInfo.put("podName", podName);
        
        sessionCache.put(labSessionId, sessionInfo);
        
        log.info("✅ Terminal session registered: labSessionId={} -> VM={}, namespace={}, pod={}", 
            labSessionId, vmName, namespace, podName);
    }

    public Map<String, String> getSession(Integer labSessionId) {
        Map<String, String> sessionInfo = sessionCache.get(labSessionId);
        
        if (sessionInfo == null) {
            log.warn("⚠️ Terminal session not found for labSessionId: {}", labSessionId);
        }
        
        return sessionInfo;
    }

    public void removeSession(Integer labSessionId) {
        Map<String, String> removed = sessionCache.remove(labSessionId);
        
        if (removed != null) {
            log.info("🧹 Terminal session removed: labSessionId={} -> VM={}", 
                labSessionId, removed.get("vmName"));
        }
    }

    public boolean exists(Integer labSessionId) {
        return sessionCache.containsKey(labSessionId);
    }
}