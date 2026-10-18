package com.pesaguard.backend.security;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.pesaguard.backend.config.ApplicationProperties;
import com.pesaguard.backend.security.sessions.RestAccessDeniedHandler;
import com.pesaguard.backend.security.sessions.RestAuthenticationEntryPoint;
import com.pesaguard.backend.security.sessions.SessionAuthenticationFilter;
import com.pesaguard.backend.security.sessions.DeveloperWorkspaceAccessFilter;
import com.pesaguard.backend.platform.PlatformRuntimeFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    FilterRegistrationBean<DeveloperWorkspaceAccessFilter> developerWorkspaceAccessFilterRegistration(
            DeveloperWorkspaceAccessFilter filter) {
        FilterRegistrationBean<DeveloperWorkspaceAccessFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SessionAuthenticationFilter sessionAuthenticationFilter,
            DeveloperWorkspaceAccessFilter developerWorkspaceAccessFilter,
            PlatformRuntimeFilter platformRuntimeFilter,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            @Qualifier("corsConfigurationSource") CorsConfigurationSource corsConfigurationSource) throws Exception {
        return http
                // Developer routes only. Without an explicit matcher this chain is
                // the fallback for ANY unmatched request, which would quietly
                // serve /internal/** through developer session auth and undo the
                // whole operator separation. Named explicitly so that stays true.
                .securityMatcher("/api/**", "/oauth/**", "/openapi/**", "/actuator/**", "/health/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
                                "/api/v1/auth/login/email-mfa/verify",
                                "/api/v1/auth/login/email-mfa/resend",
                                "/api/v1/auth/refresh", "/api/v1/auth/verify-email",
                                "/api/v1/auth/verify-email/complete-registration",
                                "/api/v1/auth/verify-email/complete-registration-link",
                                "/api/v1/auth/verify-email/resend",
                                "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password",
                                "/api/v1/auth/passkeys/login/**", "/api/v1/auth/passkeys/mfa/**",
                                "/api/v1/oauth/token", "/api/v1/oauth/revoke", "/api/v1/oauth/introspect",
                                "/api/v1/service-accounts/token",
                                "/api/v1/billing/webhooks/stripe", "/api/v1/billing/webhooks/payhero",
                                "/api/v1/billing/webhooks/daraja", "/api/v1/billing/webhooks/airtel-money")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/platform/status").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/invitations/*/preview").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/status/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/ecosystem/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/sandbox/transactions").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/support", "/api/v1/support/categories",
                                "/api/v1/support/articles", "/api/v1/support/articles/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/changelog")
                        .permitAll()
                        .requestMatchers("/api/v1/key-data/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/health/live", "/health/ready", "/openapi/**").permitAll()
                        // Production binds actuator to the private management
                        // port. NetworkPolicy limits these paths to probes and
                        // the monitoring namespace.
                        .requestMatchers(HttpMethod.GET, "/actuator/health/liveness",
                                "/actuator/health/readiness", "/actuator/prometheus").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(sessionAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(developerWorkspaceAccessFilter, SessionAuthenticationFilter.class)
                .addFilterAfter(platformRuntimeFilter, DeveloperWorkspaceAccessFilter.class)
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(ApplicationProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.security().allowedOrigins().stream()
                .map(java.net.URI::toString)
                .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // X-Workspace-ID is a client-supplied claim about which workspace a request
        // concerns. It is allowed through CORS so browsers can send it, and it is
        // re-validated server-side against an active membership -- permitting the
        // header here grants no access on its own.
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-ID",
                "X-Workspace-ID", "Idempotency-Key"));
        configuration.setExposedHeaders(List.of("X-Request-ID", "Retry-After"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
