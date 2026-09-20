package com.foleybooks.auth.token.api;

import com.foleybooks.auth.token.api.JwksResponse.JwkResponse;
import com.foleybooks.auth.token.service.RsaKeyPairProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /oauth2/jwks — the public validation key of the whole platform (D-03,
 * AGENTS.md §5). catalog-service and order-service point their OAuth2 resource
 * server at exactly this URI (JWKS_URI); the path sits on the SecurityConfig
 * public allowlist (C22) because it carries public information only — the
 * private half never appears here or anywhere outside {@link RsaKeyPairProvider}.
 * The response is cached briefly on purpose: the key changes only on redeploy.
 */
@RestController
@Tag(name = "JWKS", description = "Public signing keys for access-token validation (AGENTS.md §5)")
public class JwksController {

    private final RsaKeyPairProvider keyPairProvider;

    public JwksController(RsaKeyPairProvider keyPairProvider) {
        this.keyPairProvider = keyPairProvider;
    }

    @GetMapping("/oauth2/jwks")
    @Operation(summary = "Expose the RSA public signing key as an RFC 7517 JWKS",
            description = "Public endpoint consumed by the other services' resource servers via JWKS_URI.")
    public ResponseEntity<JwksResponse> jwks() {
        RSAPublicKey key = keyPairProvider.publicKey();
        JwkResponse jwk = JwkResponse.rsaPublic(
                keyPairProvider.keyId(),
                RsaKeyPairProvider.base64UrlUnsigned(key.getModulus()),
                RsaKeyPairProvider.base64UrlUnsigned(key.getPublicExponent()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(new JwksResponse(List.of(jwk)));
    }
}
