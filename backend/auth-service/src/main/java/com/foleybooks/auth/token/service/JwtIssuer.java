package com.foleybooks.auth.token.service;

import com.foleybooks.auth.config.JwtProperties;
import com.foleybooks.auth.user.domain.User;
import io.jsonwebtoken.Jwts;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Mints short-lived RS256 access tokens (FR-03/FR-04 basis, D-03, AU-15).
 * The claim set is exactly the one AGENTS.md §5 mandates: {@code sub} (user
 * UUID — public identifier, C-safe), {@code email}, {@code roles},
 * {@code iss}, {@code iat}, {@code exp} and a random {@code jti}, plus the
 * RFC 7638 {@code kid} header so validators can pick the right JWKS key.
 * Nothing sensitive ever goes into a claim, and the token is the only
 * session artifact other services see (stateless services, no shared lib).
 *
 * <p>Signing uses the {@link RsaKeyPairProvider} private half; consumers
 * validate via JWKS (AGENTS.md §5). The TTL comes from configuration and
 * defaults to 15 minutes. Refresh-token pairing lives with login/refresh
 * (AU-16/AU-17), not here.
 */
@Component
public class JwtIssuer {

    private final JwtProperties properties;
    private final RsaKeyPairProvider keyPairProvider;

    public JwtIssuer(JwtProperties properties, RsaKeyPairProvider keyPairProvider) {
        this.properties = properties;
        this.keyPairProvider = keyPairProvider;
    }

    /**
     * Issues a signed access token for the given user, valid for
     * {@code app.jwt.access-token-ttl} from now.
     */
    public String issue(User user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
        return Jwts.builder()
                .header().keyId(keyPairProvider.keyId()).and()
                .issuer(properties.issuer())
                .subject(user.getId().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .claim("email", user.getEmail())
                .claim("roles", List.of(user.getRole().name()))
                .signWith(keyPairProvider.privateKey(), Jwts.SIG.RS256)
                .compact();
    }
}
