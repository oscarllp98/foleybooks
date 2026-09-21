package com.foleybooks.catalog.config;

import com.foleybooks.catalog.common.ProblemDetailResponder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Deny-by-default authorization for catalog-service (D-14, C22, ADR-007, ADR-008). The permit
 * list is exactly the constitutional public allowlist for this service: catalog
 * <b>GETs</b> and actuator health/info — the MVP catalog is a seeded read model
 * (spec §6), so any other method is denied, and the admin writes of AGENTS.md §2's
 * end state arrive with a later spec on top of this same chain. Everything else,
 * including future unlisted endpoints, is denied. The GET matchers are patterns, so
 * a future non-public GET under those trees requires an explicit matcher change
 * first — the boundary and its pinning test are recorded in ADR-008.
 *
 * <p>Unlike auth-service, catalog is a resource server (AGENTS.md §2): a bearer JWT
 * is validated against the auth-service JWKS ({@code JWKS_URI} env, AGENTS.md §5), so
 * authenticated traffic is real here — a CUSTOMER token hitting a denied rule earns a
 * 403, not just a 401, and {@code /actuator/metrics} is genuinely admin-only
 * (the ADR-007 fail-closed caveat was auth-service's, not this service's). Filter-chain
 * rejections — including bearer-token failures from the resource-server filter — render
 * as ProblemDetail via {@link ProblemDetailResponder} (NFR-06). CSRF, sessions, form
 * login and HTTP Basic are off: the API is stateless JSON and carries no ambient
 * cookie credentials. CORS is not configured — it belongs to the gateway only (AGENTS.md §5).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_GET_PATHS = {
            "/api/v1/books",
            "/api/v1/books/**",
            "/api/v1/categories",
            "/api/v1/categories/**"
    };

    private static final String[] PUBLIC_ACTUATOR_PATHS = {
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
    SecurityFilterChain catalogSecurityFilterChain(HttpSecurity http,
                                                  ProblemDetailResponder problemDetailResponder,
                                                  JwtAuthenticationConverter jwtAuthenticationConverter,
                                                  @Value("${springdoc.api-docs.enabled:false}") boolean devDocsEnabled)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers(HttpMethod.GET, PUBLIC_GET_PATHS).permitAll();
                    requests.requestMatchers(PUBLIC_ACTUATOR_PATHS).permitAll();
                    if (devDocsEnabled) {
                        // Swagger UI is a dev-profile surface (AGENTS.md §6); the flag comes
                        // from springdoc.api-docs.enabled, which stays false outside dev.
                        requests.requestMatchers(DEV_DOC_PATHS).permitAll();
                    }
                    // AGENTS.md §5: metrics requires ROLE_ADMIN — enforced here for real,
                    // because the roles claim is mapped onto ROLE_-prefixed authorities
                    // (the mapping decision is ADR-008).
                    requests.requestMatchers(METRICS_PATHS).hasRole("ADMIN");
                    requests.anyRequest().denyAll();
                })
                .oauth2ResourceServer(oauth2 -> oauth2
                        // Bearer-token failures are security-filter rejections too: same
                        // ProblemDetail contract as every other 401 (ADR-007), wired on the
                        // resource-server DSL so it wins over BearerTokenAuthenticationEntryPoint
                        // — the WWW-Authenticate trade-off is recorded in ADR-008.
                        .authenticationEntryPoint(problemDetailResponder)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(problemDetailResponder)
                        .accessDeniedHandler(problemDetailResponder));
        return http.build();
    }

    /**
     * Maps the JWT {@code roles} claim (plain names — {@code ADMIN}/{@code CUSTOMER},
     * AGENTS.md §5 as issued by auth-service JwtIssuer) onto {@code ROLE_}-prefixed
     * authorities, so {@code hasRole("ADMIN")} on the metrics matcher speaks the same
     * language as the token. Without this Spring's defaults (scope-prefix only) would
     * deny every caller, including admins. The mapping is a recorded decision
     * (ADR-008): order-service copies it, and a claim rename breaks that contract.
     */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter rolesClaimConverter = new JwtGrantedAuthoritiesConverter();
        rolesClaimConverter.setAuthoritiesClaimName("roles");
        rolesClaimConverter.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(rolesClaimConverter);
        return converter;
    }
}
