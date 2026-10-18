package com.pesaguard.backend.ratelimit.application;

import org.springframework.http.HttpStatus;

import com.pesaguard.backend.common.exception.BusinessException;

/** Raised when the platform cannot safely evaluate an enforced rate limit. */
public final class RateLimitUnavailableException extends BusinessException {

    public RateLimitUnavailableException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "RATE_LIMITER_UNAVAILABLE",
                "Request throttling is temporarily unavailable.");
    }
}
