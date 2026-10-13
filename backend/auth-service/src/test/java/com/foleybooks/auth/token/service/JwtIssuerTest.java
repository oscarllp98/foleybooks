package com.foleybooks.auth.token.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foleybooks.auth.config.JwtProperties;
import com.foleybooks.auth.user.domain.User;
import com.foleybooks.auth.user.domain.UserRole;
import com.foleybooks.auth.user.domain.UserStatus;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The AU-15 access token itself: RS256, 15-minute TTL and exactly the claim
 * set AGENTS.md §5 mandates (sub/email/roles/iss/iat/exp/jti), validated by
 * parsing with the public half only — the shape the future resource-server
 * consumers will see.
 */
class JwtIssuerTest {

    private static final UUID USER_ID = UUID.fromString("0b8f4b2e-9d3a-4c1e-8a2b-1f2e3d4c5b6a");
    private static final String EMAIL = "reader@example.com";
    private static final String ISSUER = "http://localhost:8081";

    private RsaKeyPairProvider keyPairProvider;
    private JwtIssuer issuer;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties(ISSUER, Duration.ofMinutes(15), null, null);
        keyPairProvider = new RsaKeyPairProvider(properties);
        issuer = new JwtIssuer(properties, keyPairProvider);
    }

    private User user(UserRole role) {
        User user = new User(EMAIL, "$2a$12$" + "x".repeat(53), role, UserStatus.VERIFIED);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }

    private Jws<Claims> parse(String token) {
        return Jwts.parser()
                .verifyWith(keyPairProvider.publicKey())
                .build()
                .parseSignedClaims(token);
    }

    @Test
    void issue_whenCustomerRequested_returnsRs256JwtWithMandatedClaims() {
        String token = issuer.issue(user(UserRole.CUSTOMER));

        Jws<Claims> parsed = parse(token);
        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo("RS256");
        assertThat(parsed.getHeader().getKeyId()).isEqualTo(keyPairProvider.keyId());

        Claims claims = parsed.getPayload();
        assertThat(claims.getSubject()).isEqualTo(USER_ID.toString());
        assertThat(claims.getIssuer()).isEqualTo(ISSUER);
        assertThat(claims.get("email", String.class)).isEqualTo(EMAIL);
        assertThat(rolesOf(claims)).containsExactly("CUSTOMER");
        assertThat(claims.getId()).isNotBlank();
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
    }

    @Test
    void issue_whenAdminUser_rolesClaimCarriesAdmin() {
        Claims claims = parse(issuer.issue(user(UserRole.ADMIN))).getPayload();

        assertThat(rolesOf(claims)).containsExactly("ADMIN");
    }

    @Test
    void issue_whenTtlConfigured_expiresFifteenMinutesAfterIssue() {
        Instant before = Instant.now().minusSeconds(5);

        Claims claims = parse(issuer.issue(user(UserRole.CUSTOMER))).getPayload();

        Duration ttl = Duration.between(claims.getIssuedAt().toInstant(), claims.getExpiration().toInstant());
        assertThat(ttl.toSeconds()).isEqualTo(15 * 60);
        assertThat(claims.getIssuedAt().toInstant()).isAfterOrEqualTo(before);
    }

    @Test
    void issue_whenCalledTwice_producesDistinctJti() {
        User customer = user(UserRole.CUSTOMER);

        String first = issuer.issue(customer);
        String second = issuer.issue(customer);

        assertThat(parse(first).getPayload().getId())
                .isNotEqualTo(parse(second).getPayload().getId());
    }

    @Test
    void issue_whenVerifiedWithForeignKeyPair_failsSignatureValidation() {
        String token = issuer.issue(user(UserRole.CUSTOMER));
        RsaKeyPairProvider stranger = new RsaKeyPairProvider(
                new JwtProperties(ISSUER, Duration.ofMinutes(15), null, null));

        assertThatThrownBy(() -> Jwts.parser()
                .verifyWith(stranger.publicKey())
                .build()
                .parseSignedClaims(token))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void issue_whenPayloadTampered_failsSignatureValidation() {
        String token = issuer.issue(user(UserRole.CUSTOMER));
        String[] parts = token.split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"evil\",\"roles\":[\"ADMIN\"]}".getBytes(StandardCharsets.UTF_8));

        String tampered = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThatThrownBy(() -> parse(tampered))
                .isInstanceOf(SignatureException.class);
    }

    @SuppressWarnings("unchecked")
    private static List<String> rolesOf(Claims claims) {
        return (List<String>) claims.get("roles", List.class);
    }
}
