package com.pesaguard.backend.events.api;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.common.api.PageResponse;
import com.pesaguard.backend.events.application.EventPlatformService;
import com.pesaguard.backend.events.application.EventPlatformService.DeliveryView;
import com.pesaguard.backend.events.application.EventPlatformService.EventRecord;
import com.pesaguard.backend.events.application.EventPlatformService.EventTypeView;
import com.pesaguard.backend.events.application.EventPlatformService.SubscriptionView;
import com.pesaguard.backend.events.application.EventPlatformService.CreateSubscription;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/v1/events")
public class EventPlatformController {

    private final EventPlatformService service;

    public EventPlatformController(EventPlatformService service) {
        this.service = service;
    }

    @GetMapping("/catalog")
    ApiResponse<List<EventTypeView>> catalog(@AuthenticationPrincipal AuthenticatedUser principal) {
        return ApiResponse.of(service.catalog(principal));
    }

    @GetMapping
    ApiResponse<PageResponse<EventRecord>> events(@AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam UUID projectId,
            @RequestParam UUID environmentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.of(service.events(principal, projectId, environmentId, page, size));
    }

    @GetMapping("/subscriptions")
    ApiResponse<PageResponse<SubscriptionView>> subscriptions(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam UUID projectId, @RequestParam UUID environmentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.of(service.subscriptions(principal, projectId, environmentId, page, size));
    }

    @PostMapping("/subscriptions")
    ApiResponse<SubscriptionView> createSubscription(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateSubscription request) {
        return ApiResponse.of(service.createSubscription(principal, request));
    }

    @PatchMapping("/subscriptions/{subscriptionId}/status")
    ApiResponse<SubscriptionView> changeSubscriptionStatus(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID subscriptionId,
            @RequestParam UUID environmentId,
            @Valid @RequestBody ChangeSubscriptionStatusRequest request) {
        return ApiResponse.of(service.changeSubscriptionStatus(
                principal, subscriptionId, environmentId, request.status()));
    }

    @GetMapping("/deliveries")
    ApiResponse<PageResponse<DeliveryView>> deliveries(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam UUID projectId,
            @RequestParam UUID environmentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.of(service.deliveries(principal, projectId, environmentId, page, size));
    }

    @PostMapping("/deliveries/{deliveryId}/replay")
    ApiResponse<DeliveryView> replayDelivery(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID deliveryId, @RequestParam UUID environmentId) {
        return ApiResponse.of(service.replayDelivery(principal, deliveryId, environmentId));
    }

    public record ChangeSubscriptionStatusRequest(@NotBlank String status) {
    }
}
