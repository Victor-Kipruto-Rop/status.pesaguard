package com.pesaguard.backend.platformadmin.api;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Network-isolation friendly liveness response for the operator plane. It is
 * deliberately independent of tenant data and stays available without an
 * operator token so infrastructure can distinguish an unavailable control plane
 * from an authorization failure.
 */
@RestController
@RequestMapping("/internal")
public class OperatorHealthController {
    @GetMapping("/health")
    Map<String, Object> health() {
        return Map.of("status", "UP", "timestamp", Instant.now().toString());
    }
}
