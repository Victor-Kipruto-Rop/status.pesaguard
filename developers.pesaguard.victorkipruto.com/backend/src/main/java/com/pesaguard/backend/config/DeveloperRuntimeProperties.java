package com.pesaguard.backend.config;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pesaguard.runtime")
public class DeveloperRuntimeProperties {
    private boolean maintenanceMode;
    private String maintenanceMessage = "The developer platform is temporarily unavailable while updates are being applied.";
    private Instant maintenanceStartsAt;
    private Instant estimatedRecoveryAt;
    private Map<String, Boolean> features = new LinkedHashMap<>();

    public boolean isMaintenanceMode() { return maintenanceMode; }
    public void setMaintenanceMode(boolean maintenanceMode) { this.maintenanceMode = maintenanceMode; }
    public String getMaintenanceMessage() { return maintenanceMessage; }
    public void setMaintenanceMessage(String maintenanceMessage) { this.maintenanceMessage = maintenanceMessage; }
    public Instant getMaintenanceStartsAt() { return maintenanceStartsAt; }
    public void setMaintenanceStartsAt(Instant maintenanceStartsAt) { this.maintenanceStartsAt = maintenanceStartsAt; }
    public Instant getEstimatedRecoveryAt() { return estimatedRecoveryAt; }
    public void setEstimatedRecoveryAt(Instant estimatedRecoveryAt) { this.estimatedRecoveryAt = estimatedRecoveryAt; }
    public Map<String, Boolean> getFeatures() { return features; }
    public void setFeatures(Map<String, Boolean> features) { this.features = features == null ? new LinkedHashMap<>() : new LinkedHashMap<>(features); }

    public boolean isFeatureEnabled(String feature) {
        return features.getOrDefault(feature, true);
    }
}
