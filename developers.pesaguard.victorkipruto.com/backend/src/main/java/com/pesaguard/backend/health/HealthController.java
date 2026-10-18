package com.pesaguard.backend.health;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/health/live")
    Map<String, Object> live() {
        return Map.of("status", "UP", "checkedAt", Instant.now());
    }

    @GetMapping("/health/ready")
    ResponseEntity<Map<String, Object>> ready() {
        try {
            jdbcTemplate.queryForObject("select 1", Integer.class);
            return ResponseEntity.ok(Map.of("status", "UP", "checkedAt", Instant.now()));
        } catch (RuntimeException exception) {
            return ResponseEntity.status(503).body(Map.of(
                    "status", "DOWN",
                    "checkedAt", Instant.now()));
        }
    }
}
