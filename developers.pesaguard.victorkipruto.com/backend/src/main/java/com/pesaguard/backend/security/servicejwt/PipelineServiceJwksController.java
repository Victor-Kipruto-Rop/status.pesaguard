package com.pesaguard.backend.security.servicejwt;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PipelineServiceJwksController {

    public static final String JWKS_PATH = "/internal/v1/service-jwt/jwks.json";

    private final PipelineServiceJwtService serviceJwtService;

    public PipelineServiceJwksController(PipelineServiceJwtService serviceJwtService) {
        this.serviceJwtService = serviceJwtService;
    }

    @GetMapping(JWKS_PATH)
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofSeconds(60)).cachePublic())
                .body(serviceJwtService.publicJwks());
    }
}
