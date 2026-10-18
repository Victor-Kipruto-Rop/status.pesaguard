package com.pesaguard.backend.analytics.infrastructure;

import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.pesaguard.backend.analytics.application.UsageEventRecorder;
import com.pesaguard.backend.analytics.domain.ApiRequestEvent;
import com.pesaguard.backend.common.api.RequestContext;
import com.pesaguard.backend.tenancy.TenantContext;
import com.pesaguard.backend.tenancy.TenantContextHolder;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Captures every API request as a usage event.
 *
 * <p>Runs outside any transaction and only enqueues, so it cannot make a request
 * slower or fail it.
 *
 * <p><b>Telemetry must never be able to fail the request.</b> Every failure path
 * here logs and returns rather than propagating. An exception escaping this
 * filter would turn an analytics fault into a payment API outage, and an
 * observability subsystem that can take down the system it observes is worse
 * than no observability at all.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class UsageTrackingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UsageTrackingFilter.class);

    private static final int MAX_ENDPOINT_LENGTH = 256;

    private final ObjectProvider<UsageEventRecorder> recorderProvider;
    private final ObjectProvider<RequestAttribution> attributionProvider;
    private final Clock clock;
    private final AtomicBoolean warnedAboutMissingRecorder = new AtomicBoolean();

    public UsageTrackingFilter(ObjectProvider<UsageEventRecorder> recorderProvider,
            ObjectProvider<RequestAttribution> attributionProvider, Clock clock) {
        this.recorderProvider = recorderProvider;
        this.attributionProvider = attributionProvider;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            // In a finally block so a request that throws is still counted. An
            // exception here would mask the original.
            try {
                record(request, response, startedAt);
            } catch (RuntimeException failure) {
                log.warn("failed to record usage event for {} {}", request.getMethod(),
                        safePath(request), failure);
            }
        }
    }


    private void record(HttpServletRequest request, HttpServletResponse response, long startedAt) {
        UsageEventRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null) {
            if (warnedAboutMissingRecorder.compareAndSet(false, true)) {
                log.warn("usage event recorder unavailable; request usage is not being recorded");
            }
            return;
        }

        RequestAttribution.Resolved attribution = attribute(request);
        if (attribution == null || attribution.organizationId() == null || attribution.environmentId() == null) {
            return;
        }

        recorder.record(ApiRequestEvent.record(
                requestIdFor(request),
                attribution.organizationId(),
                attribution.projectId(),
                attribution.environmentId(),
                attribution.apiKeyId(),
                attribution.oauthApplicationId(),
                safePath(request),
                request.getMethod(),
                response.getStatus(),
                latencyMs(startedAt),
                responseBytes(response),
                clock.instant(),
                attribution.userId()));
    }

    private String requestIdFor(HttpServletRequest request) {
    /**
     * The idempotency key.
     *
     * <p>Reuses the correlation id that {@code CorrelationIdFilter} already resolved
     * from {@code X-Request-ID}, or generated when the client sent none.
     *
     * <p><b>Known limitation.</b> Without a stable client-supplied
     * {@code X-Request-ID}, each retry receives a freshly generated UUID and is
     * counted separately. This is a property of HTTP retries, not something a
     * server can reconstruct: two identical requests are indistinguishable from
     * two genuine ones.
     */
        UUID requestId = RequestContext.currentRequestId();
        return requestId == null ? UUID.randomUUID().toString() : requestId.toString();
    }

    private RequestAttribution.Resolved attribute(HttpServletRequest request) {
        RequestAttribution resolver = attributionProvider.getIfAvailable();
        if (resolver != null) {
            return resolver.resolve(request);
        }
        java.util.Optional<TenantContext> context = TenantContextHolder.current();
        if (context.isEmpty()) {
            return null;
        }
        TenantContext value = context.get();
        return new RequestAttribution.Resolved(value.organizationId(), value.projectId(),
                value.environmentId(), null, null, value.actorId());
    }

    private long elapsedMs(long startedAtNanos) {
        return Math.max(0, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startedAtNanos));
    }

    /**
     * Latency clamped to what the column can hold.
     *
     * <p>A request that appears to take more than {@link Integer#MAX_VALUE} ms
     * has almost certainly wedged a timer rather than genuinely run for 24 days.
     * Clamping keeps the row insertable instead of failing it, which would lose
     * the event entirely.
     */
    private int latencyMs(long startedAtNanos) {
        return (int) Math.min(Integer.MAX_VALUE, elapsedMs(startedAtNanos));
    }

    private Long responseBytes(HttpServletResponse response) {
        String header = response.getHeader("Content-Length");
        if (header == null) {
            return null;
        }
        try {
            long length = Long.parseLong(header.trim());
            return length < 0 ? null : length;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private String safePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || uri.isBlank()) {
            return "/";
        }
        return uri.length() <= MAX_ENDPOINT_LENGTH ? uri : uri.substring(0, MAX_ENDPOINT_LENGTH);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && (path.startsWith("/actuator") || path.equals("/health"));
    }
}
