package com.foleybooks.auth.config;

import com.foleybooks.auth.common.ProblemDetailResponder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Deny-by-default authorization for auth-service (D-14, C22, ADR-007). The permit list
 * is exactly the constitutional public allowlist for this service: the six auth flows,
 * the JWKS endpoint and actuator health/info. "Public" means no valid access token is
 * required — refresh and logout authenticate with their own opaque refresh token in the
 * request body (FR-04, FR-05, D-05, LC-21). Everything else, including future unlisted
 * endpoints, is denied. CSRF, sessions, form login and HTTP Basic are off: the API is
 * stateless JSON and carries no ambient cookie credentials. CORS is not configured —
 * it belongs to the gateway only (AGENTS.md §5). Filter-chain rejections are rendered
 * as ProblemDetail by {@link ProblemDetailResponder} (NFR-06).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/register",
            "/api/v1/auth/confirm",
            "/api/v1/auth/resend",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/oauth2/jwks",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info"
    };

    private static final String[] DEV_DOC_PATHS = {
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    private static final String[] METRICS_PATHS = {
            "/actuator/metrics",
            "/actuator/metrics/**"
    };

    @Bean
    SecurityFilterChain authSecurityFilterChain(HttpSecurity http,
                                                ProblemDetailResponder problemDetailResponder,
                                                @Value("${springdoc.api-docs.enabled:false}") boolean devDocsEnabled)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers(PUBLIC_PATHS).permitAll();
                    if (devDocsEnabled) {
                        // Swagger UI is a dev-profile surface (AGENTS.md §6); the flag comes
                        // from springdoc.api-docs.enabled, which stays false outside dev.
                        requests.requestMatchers(DEV_DOC_PATHS).permitAll();
                    }
                    // AGENTS.md §5: metrics requires ROLE_ADMIN. auth-service has no token
                    // validation (AGENTS.md §2), so this stays fail-closed until it does (ADR-007).
                    requests.requestMatchers(METRICS_PATHS).hasRole("ADMIN");
                    requests.anyRequest().denyAll();
                })
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(problemDetailResponder)
                        .accessDeniedHandler(problemDetailResponder));
        return http.build();
    }

    /**
     * Auth-service authenticates no HTTP users — every endpoint is either public or
     * refresh-token-authenticated at the service layer. An empty user store keeps Spring
     * Boot from generating and logging a dev password nobody can use (C24).
     */
    @Bean
    UserDetailsService authHasNoHttpUsers() {
        return new InMemoryUserDetailsManager();
    }
}
