package com.foleybooks.auth.token.api;

import java.util.List;

/**
 * RFC 7517 JSON Web Key Set — the public half of the signing key, published
 * for the other services (and the gateway) to validate access tokens without
 * sharing keys or databases (AGENTS.md §5, D-03). Records only (C9): this is
 * the serialized form, the JCA key objects never leave the service layer.
 *
 * @param keys the active signing keys — exactly one while the service holds a
 *             single keypair; a list because that is what RFC 7517 specifies
 */
public record JwksResponse(List<JwkResponse> keys) {

    /**
     * One RSA public key in JWK form. The RFC 7638 thumbprint of {@code n}/
     * {@code e} is the {@code kid} that also appears in every token header.
     */
    public record JwkResponse(String kty, String use, String alg, String kid, String n, String e) {

        public static JwkResponse rsaPublic(String kid, String modulus, String exponent) {
            return new JwkResponse("RSA", "sig", "RS256", kid, modulus, exponent);
        }
    }
}
