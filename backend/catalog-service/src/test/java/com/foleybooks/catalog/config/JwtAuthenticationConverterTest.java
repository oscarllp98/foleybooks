package com.foleybooks.catalog.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code roles} claim produced by auth-service's JwtIssuer carries plain names
 * ({@code ADMIN}/{@code CUSTOMER}, AGENTS.md §5); the metrics matcher and future
 * {@code @PreAuthorize} rules speak {@code hasRole('ADMIN')}. This test pins the
 * mapping SecurityConfig wires between the two (D-14).
 */
class JwtAuthenticationConverterTest {

    private final JwtAuthenticationConverter converter = new SecurityConfig().jwtAuthenticationConverter();

    @Test
    void convert_whenRolesClaimPresent_mapsToRolePrefixedAuthorities() {
        Jwt jwt = jwtWithClaims("roles", List.of("ADMIN", "CUSTOMER"));

        assertThat(converter.convert(jwt).getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN", "ROLE_CUSTOMER");
    }

    @Test
    void convert_whenRolesClaimMissing_grantsNoAuthorities() {
        Jwt jwt = jwtWithClaims("email", "reader@example.com");

        assertThat(converter.convert(jwt).getAuthorities()).isEmpty();
    }

    private static Jwt jwtWithClaims(String name, Object value) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject("0b8f4b2e-9d3a-4c1e-8a2b-1f2e3d4c5b6a")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim(name, value)
                .build();
    }
}
