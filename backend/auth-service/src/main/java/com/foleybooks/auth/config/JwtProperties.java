package com.foleybooks.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Access-JWT settings (D-03, AGENTS.md §5, AU-15). Bound from {@code app.jwt.*}
 * per the AGENTS.md §3 config convention. The PEM pair is supplied via
 * {@code JWT_PRIVATE_KEY}/{@code JWT_PUBLIC_KEY} in real deployments; both stay
 * blank in dev and {@link com.foleybooks.auth.token.service.RsaKeyPairProvider}
 * generates an ephemeral pair at startup instead — no committed keys (C19).
 *
 * @param issuer        the {@code iss} claim value; other services validate via
 *                      the JWKS URI, not the issuer, so this is informational
 * @param accessTokenTtl lifetime of an access token — 15 minutes per AGENTS.md §5
 * @param privateKeyPem PKCS#8 RSA private key PEM ("BEGIN PRIVATE KEY"); blank ⇒ auto-generate
 * @param publicKeyPem  X.509 RSA public key PEM ("BEGIN PUBLIC KEY"); blank ⇒ derived from the private key
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String issuer,
        Duration accessTokenTtl,
        String privateKeyPem,
        String publicKeyPem) {
}
