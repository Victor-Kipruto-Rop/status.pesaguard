package com.pesaguard.backend.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pesaguard.pipeline.service-jwt")
public class PipelineServiceJwtProperties {

    private String mode = "HMAC_ONLY";
    private String activeKid;
    private String privateKeyPem;
    private int ttlSeconds = 120;
    private Map<String, String> overlapPublicKeys = new LinkedHashMap<>();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getActiveKid() {
        return activeKid;
    }

    public void setActiveKid(String activeKid) {
        this.activeKid = activeKid;
    }

    public String getPrivateKeyPem() {
        return privateKeyPem;
    }

    public void setPrivateKeyPem(String privateKeyPem) {
        this.privateKeyPem = privateKeyPem;
    }

    public int getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(int ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public Map<String, String> getOverlapPublicKeys() {
        return overlapPublicKeys;
    }

    public void setOverlapPublicKeys(Map<String, String> overlapPublicKeys) {
        this.overlapPublicKeys = overlapPublicKeys == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(overlapPublicKeys);
    }
}
