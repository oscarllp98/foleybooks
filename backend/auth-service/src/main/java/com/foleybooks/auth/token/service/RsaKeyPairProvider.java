package com.foleybooks.auth.token.service;

import com.foleybooks.auth.config.JwtProperties;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The service's RSA signing keypair (D-03, AGENTS.md §5, AU-15). Two sources:
 * PEM values from the environment ({@code JWT_PRIVATE_KEY} + optional
 * {@code JWT_PUBLIC_KEY}) for real deployments, or — when no private PEM is
 * configured — an ephemeral 2048-bit pair generated at startup so dev boots
 * with zero key setup. No key material is ever committed (C19) or logged (C24);
 * only the key <em>id</em> is published.
 *
 * <p>The key id follows RFC 7638: the base64url SHA-256 thumbprint of the
 * public JWK, so it is deterministic for a given key — stable across restarts
 * when the PEM comes from env — and the JWKS published by the token API always
 * carries the same {@code kid} that signs access tokens. The private half
 * never leaves this service; every other service validates with the public
 * half via JWKS (C22, AGENTS.md §5).
 */
@Component
public class RsaKeyPairProvider {

    private static final Logger log = LoggerFactory.getLogger(RsaKeyPairProvider.class);

    private static final String RSA = "RSA";
    private static final int KEY_SIZE_BITS = 2048;
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final PrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final String keyId;

    public RsaKeyPairProvider(JwtProperties properties) {
        String privatePem = blankToNull(properties.privateKeyPem());
        String publicPem = blankToNull(properties.publicKeyPem());
        if (privatePem == null) {
            if (publicPem != null) {
                throw new IllegalStateException(
                        "app.jwt.public-key-pem is set without app.jwt.private-key-pem — "
                                + "auth-service cannot sign access tokens with only a public key");
            }
            KeyPair generated = generateKeyPair();
            this.privateKey = generated.getPrivate();
            this.publicKey = (RSAPublicKey) generated.getPublic();
            log.warn("No JWT_PRIVATE_KEY configured — generated an ephemeral RSA keypair. "
                    + "Access tokens will not survive a restart. Dev only; real deployments "
                    + "must supply JWT_PRIVATE_KEY (PEM) via the environment.");
        } else {
            this.privateKey = parsePrivateKey(privatePem);
            this.publicKey = publicPem != null
                    ? parseAndMatchPublicKey(publicPem)
                    : derivePublicKey();
        }
        this.keyId = thumbprintKeyId();
    }

    public PrivateKey privateKey() {
        return privateKey;
    }

    public RSAPublicKey publicKey() {
        return publicKey;
    }

    /** RFC 7638 thumbprint of the public key; doubles as the JWKS {@code kid}. */
    public String keyId() {
        return keyId;
    }

    /** Base64url (no padding) unsigned big-endian magnitude — the JWK encoding of RSA parts. */
    public static String base64UrlUnsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        // toByteArray() prepends a 0x00 sign byte on high-bit values; JWK wants the bare magnitude.
        if (bytes[0] != 0) {
            return URL_ENCODER.encodeToString(bytes);
        }
        return URL_ENCODER.encodeToString(Arrays.copyOfRange(bytes, 1, bytes.length));
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(RSA);
            generator.initialize(KEY_SIZE_BITS);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM has no RSA KeyPairGenerator", e);
        }
    }

    private static PrivateKey parsePrivateKey(String pem) {
        try {
            return KeyFactory.getInstance(RSA)
                    .generatePrivate(new PKCS8EncodedKeySpec(decodePem(pem, "PRIVATE KEY")));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException e) {
            throw new IllegalStateException("JWT_PRIVATE_KEY is not a valid PKCS#8 RSA private key PEM", e);
        }
    }

    private RSAPublicKey parseAndMatchPublicKey(String pem) {
        RSAPublicKey parsed;
        try {
            parsed = (RSAPublicKey) KeyFactory.getInstance(RSA)
                    .generatePublic(new X509EncodedKeySpec(decodePem(pem, "PUBLIC KEY")));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | ClassCastException e) {
            throw new IllegalStateException("JWT_PUBLIC_KEY is not a valid X.509 RSA public key PEM", e);
        }
        if (!parsed.getModulus().equals(((RSAPrivateCrtKey) privateKey).getModulus())) {
            throw new IllegalStateException("JWT_PUBLIC_KEY does not match JWT_PRIVATE_KEY");
        }
        return parsed;
    }

    private RSAPublicKey derivePublicKey() {
        if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
            throw new IllegalStateException(
                    "JWT_PRIVATE_KEY is not an RSA CRT key and no JWT_PUBLIC_KEY was given — "
                            + "the public half cannot be derived");
        }
        try {
            return (RSAPublicKey) KeyFactory.getInstance(RSA)
                    .generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Unable to derive the public key from JWT_PRIVATE_KEY", e);
        }
    }

    /**
     * Accepts a PEM block with real or {@code \n}-escaped newlines (single-line
     * env values, as documented in .env.example), strips the armor and returns
     * the DER bytes.
     */
    private static byte[] decodePem(String pem, String armor) {
        String normalized = pem.replace("\\r", "").replace("\\n", "\n");
        StringBuilder base64 = new StringBuilder();
        boolean inBlock = false;
        for (String line : normalized.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.equals("-----BEGIN " + armor + "-----")) {
                inBlock = true;
            } else if (trimmed.equals("-----END " + armor + "-----")) {
                break;
            } else if (inBlock) {
                base64.append(trimmed.replaceAll("\\s", ""));
            }
        }
        if (base64.isEmpty()) {
            throw new IllegalArgumentException("No -----BEGIN " + armor + "----- block found");
        }
        return Base64.getMimeDecoder().decode(base64.toString());
    }

    private String thumbprintKeyId() {
        try {
            // RFC 7638 §3: required members only, lexicographic order, no whitespace.
            String canonical = "{\"e\":\"%s\",\"kty\":\"RSA\",\"n\":\"%s\"}".formatted(
                    base64UrlUnsigned(publicKey.getPublicExponent()),
                    base64UrlUnsigned(publicKey.getModulus()));
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.US_ASCII));
            return URL_ENCODER.encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM has no SHA-256 digest", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
