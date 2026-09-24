package com.foleybooks.order.config;

import com.foleybooks.order.common.ProblemDetailResponder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Deny-by-default authorization for order-service (D-14, C22, ADR-007, ADR-008). Unlike
 * catalog's public read model, the cart is private per-user state (ADR-004): every
 * {@code /api/v1/cart} exchange requires a valid access token — the caller's {@code sub}
 * claim is what selects <em>whose</em> cart it is, so there is no public cart path and no
 * cart id in the API surface at all. Everything outside the cart tree, actuator health/info
 * and the dev-profile docs surface is denied ({@code anyRequest().denyAll()}), including
 * future unlisted endpoints.
 *
 * <p>order-service copies the catalog resource-server shape verbatim as ADR-008 binds it
 * to OR-03: a bearer JWT is validated against the auth-service JWKS ({@code JWKS_URI} env,
 * AGENTS.md §5), filter-chain rejections render as ProblemDetail via
 * {@link ProblemDetailResponder} (NFR-06), and {@code /actuator/metrics} is genuinely
 * ROLE_ADMIN-gated. {@link EnableMethodSecurity} is switched on here — deferred in catalog
 * because no annotated operation existed (ADR-008) — so the cart operations of
 * OR-06..OR-09 can carry their {@code @PreAuthorize} guards, with the {@code roles}
 * claim → {@code ROLE_} mapping wired below (ADR-008). CSRF, sessions, form login and
 * HTTP Basic are off: the API is stateless JSON and carries no ambient cookie
 * credentials. CORS is not configured — it belongs to the gateway only (AGENTS.md §5).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] CART_PATHS = {
            "/api/v1/cart",
            "/api/v1/cart/**"
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
    SecurityFilterChain orderSecurityFilterChain(HttpSecurity http,
                                                 ProblemDetailResponder problemDetailResponder,
                                                 JwtAuthenticationConverter jwtAuthenticationConverter,
                                                 @Value("${springdoc.api-docs.enabled:false}") boolean devDocsEnabled)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> {
                    // FR-10..FR-13 / LC-27: the whole cart tree is authenticated-only —
                    // any method, any depth. A bad or missing token is a 401 here; the
                    // owner's own cart never needs a role, so no role matcher is needed.
                    requests.requestMatchers(CART_PATHS).authenticated();
                    requests.requestMatchers(PUBLIC_ACTUATOR_PATHS).permitAll();
                    if (devDocsEnabled) {
                        // Swagger UI is a dev-profile surface (AGENTS.md §6); the flag comes
                        // from springdoc.api-docs.enabled, which stays false outside dev.
                        requests.requestMatchers(DEV_DOC_PATHS).permitAll();
                    }
                    // AGENTS.md §5: metrics requires ROLE_ADMIN — enforced for real because
                    // the roles claim is mapped onto ROLE_-prefixed authorities (ADR-008).
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
     * authorities, so {@code hasRole("ADMIN")} on the metrics matcher and the cart
     * {@code @PreAuthorize} rules speak the same language as the token. Copied from
     * catalog per ADR-008 ("order-service copies it, and a claim rename breaks that
     * contract") and pinned by {@code JwtAuthenticationConverterTest}.
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
