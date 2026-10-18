package com.pesaguard.backend.platformadmin.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.pesaguard.backend.platformadmin.domain.OperatorCapability;
import com.pesaguard.backend.security.servicejwt.PipelineServiceJwksController;

/**
 * The internal operator filter chain.
 *
 * <p><b>This is the separation.</b> It is scoped by {@code securityMatcher} to
 * {@code /internal/**} and ordered first, so a request to an operator endpoint can
 * only ever be handled here. It is not a set of rules inside the developer chain
 * where a later edit could reorder or bypass them.
 *
 * <p>Consequences worth stating, because they are the point:
 *
 * <ul>
 *   <li>a developer session token presented here is <b>not parsed</b> — the
 *       operator filter only understands operator tokens;</li>
 *   <li>the developer session filter is not in this chain, so no developer session
 *       can be established here even if a token were accepted;</li>
 *   <li>CSRF is disabled and the session is stateless, because there is no browser
 *       session to protect and no cookie an operator could be tricked into
 *       sending.</li>
 * </ul>
 *
 * <p>Every capability is matched <b>explicitly</b>, and the chain ends in
 * {@code denyAll()}. That makes it fail closed by construction: an endpoint added
 * under the prefix without a rule here matches nothing and is refused, rather than
 * becoming reachable to every operator by accident.
 *
 * <p>Network-level separation is still recommended as a second layer. This is an
 * application boundary — correct regardless of topology — but an operator endpoint
 * that is also unreachable at the network is better still.
 */
@Configuration
public class OperatorSecurityConfig {

    /** The only prefix that carries internal operator capabilities. */
    public static final String OPERATOR_PATH_PREFIX = "/internal";

    /** A liveness path that must stay reachable without a token. */
    public static final String HEALTH_PATH = OPERATOR_PATH_PREFIX + "/health";

    /** The Spring authority string for a capability. */
    public static String authority(OperatorCapability capability) {
        return "PLATFORM_" + capability.name();
    }

    @Bean
    @Order(1)
    SecurityFilterChain operatorFilterChain(
            HttpSecurity http,
            OperatorTokenService tokenService) throws Exception {
        return http
                .securityMatcher(OPERATOR_PATH_PREFIX + "/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HEALTH_PATH).permitAll()
                        .requestMatchers(HttpMethod.GET, PipelineServiceJwksController.JWKS_PATH).permitAll()
                        .requestMatchers(OPERATOR_PATH_PREFIX + "/organizations/**")
                        .hasAuthority(authority(OperatorCapability.ORGANIZATIONS_READ))
                        .requestMatchers(OPERATOR_PATH_PREFIX + "/projects/**")
                        .hasAuthority(authority(OperatorCapability.PROJECTS_READ))
                        .requestMatchers(OPERATOR_PATH_PREFIX + "/webhooks/**")
                        .hasAuthority(authority(OperatorCapability.WEBHOOKS_READ))
                        .requestMatchers(OPERATOR_PATH_PREFIX + "/usage/**")
                        .hasAuthority(authority(OperatorCapability.USAGE_READ))
                        .requestMatchers(OPERATOR_PATH_PREFIX + "/production-access/**")
                        .hasAuthority(authority(OperatorCapability.PRODUCTION_READ))
                        .requestMatchers(OPERATOR_PATH_PREFIX + "/support/tickets/*/resolve")
                        .hasAuthority(authority(OperatorCapability.SUPPORT_RESOLVE))
                        .requestMatchers(OPERATOR_PATH_PREFIX + "/support/tickets/**")
                        .hasAuthority(authority(OperatorCapability.SUPPORT_READ))
                        .requestMatchers(HttpMethod.GET, OPERATOR_PATH_PREFIX + "/feedback/**")
                        .hasAuthority(authority(OperatorCapability.SUPPORT_READ))
                        .requestMatchers(HttpMethod.POST, OPERATOR_PATH_PREFIX + "/feedback/**")
                        .hasAuthority(authority(OperatorCapability.SUPPORT_RESOLVE))
                        .requestMatchers(HttpMethod.PATCH, OPERATOR_PATH_PREFIX + "/feedback/**")
                        .hasAuthority(authority(OperatorCapability.SUPPORT_RESOLVE))
                        .requestMatchers(OPERATOR_PATH_PREFIX + "/security-events/**")
                        .hasAuthority(authority(OperatorCapability.SECURITY_READ))
                        // Mutation paths are matched before the read path so a
                        // read-only credential cannot satisfy a write.
                        .requestMatchers(OPERATOR_PATH_PREFIX + "/credentials/*/suspend")
                        .hasAuthority(authority(OperatorCapability.CREDENTIALS_SUSPEND))
                        .requestMatchers(OPERATOR_PATH_PREFIX + "/credentials/*/revoke")
                        .hasAuthority(authority(OperatorCapability.CREDENTIALS_REVOKE))
                        .requestMatchers(OPERATOR_PATH_PREFIX + "/security-events/*/resolve")
                        .hasAuthority(authority(OperatorCapability.SECURITY_RESOLVE))
                        .requestMatchers(HttpMethod.POST, OPERATOR_PATH_PREFIX + "/configuration/**")
                        .hasAuthority(authority(OperatorCapability.PLATFORM_CONFIG_WRITE))
                        .requestMatchers(HttpMethod.PUT, OPERATOR_PATH_PREFIX + "/configuration/**")
                        .hasAuthority(authority(OperatorCapability.PLATFORM_CONFIG_WRITE))
                        .requestMatchers(HttpMethod.PATCH, OPERATOR_PATH_PREFIX + "/configuration/**")
                        .hasAuthority(authority(OperatorCapability.PLATFORM_CONFIG_WRITE))
                        .requestMatchers(HttpMethod.POST, OPERATOR_PATH_PREFIX + "/billing/**")
                        .hasAuthority(authority(OperatorCapability.BILLING_WRITE))
                        .requestMatchers(HttpMethod.PUT, OPERATOR_PATH_PREFIX + "/billing/**")
                        .hasAuthority(authority(OperatorCapability.BILLING_WRITE))
                        .requestMatchers(HttpMethod.PATCH, OPERATOR_PATH_PREFIX + "/billing/**")
                        .hasAuthority(authority(OperatorCapability.BILLING_WRITE))
                        .requestMatchers(HttpMethod.DELETE, OPERATOR_PATH_PREFIX + "/billing/**")
                        .hasAuthority(authority(OperatorCapability.BILLING_WRITE))
                        .requestMatchers(OPERATOR_PATH_PREFIX + "/billing/**")
                        .hasAuthority(authority(OperatorCapability.BILLING_READ))
                        .requestMatchers(OPERATOR_PATH_PREFIX + "/configuration/**")
                        .hasAuthority(authority(OperatorCapability.PLATFORM_CONFIG_READ))
                        .requestMatchers(OPERATOR_PATH_PREFIX + "/credentials/**")
                        .hasAuthority(authority(OperatorCapability.CREDENTIALS_READ))
                        // Fail closed.
                        .anyRequest().denyAll())
                .addFilterBefore(new OperatorAuthenticationFilter(tokenService),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
