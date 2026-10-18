package com.pesaguard.backend.loadtest.api;

import java.time.Instant;
import java.util.Map;

public record LoadTestResultView(Map<String, Object> summary, String verdict, Instant recordedAt) {
}
