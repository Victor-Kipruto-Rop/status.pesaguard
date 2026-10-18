package com.pesaguard.backend.platform;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.config.DeveloperRuntimeProperties;

@RestController
@RequestMapping("/api/v1/platform")
public class RuntimeStatusController {
    private final DeveloperRuntimeProperties properties;

    public RuntimeStatusController(DeveloperRuntimeProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/status")
    public ApiResponse<RuntimeStatus> status() {
        return ApiResponse.of(new RuntimeStatus(properties.isMaintenanceMode(), properties.getMaintenanceMessage(),
                properties.getMaintenanceStartsAt(), properties.getEstimatedRecoveryAt(), properties.getFeatures()));
    }

    public record RuntimeStatus(boolean maintenanceMode, String maintenanceMessage, Instant maintenanceStartsAt,
            Instant estimatedRecoveryAt, Map<String, Boolean> features) {
        public RuntimeStatus {
            features = Map.copyOf(features);
        }
    }
}
