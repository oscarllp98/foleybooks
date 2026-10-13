package com.foleybooks.auth.token.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The opaque-token primitives shared by both hashed-at-rest token kinds —
 * email-confirmation links (FR-02) and refresh sessions (FR-04). A value is
 * 32 {@link SecureRandom} bytes rendered as 64 lowercase hex chars: 256 bits
 * of entropy (AGENTS.md §5), URL-safe for the confirmation link. Only
 * {@link #sha256Hex} ever reaches the database — the raw value exists once,
 * in the caller's hand, destined for an email body or an HTTP response, and
 * is never persisted or logged (C24).
 */
final class OpaqueTokens {

    private static final int TOKEN_BYTES = 32;
    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private OpaqueTokens() {
        // Static utilities only.
    }

    /** A fresh opaque 256-bit value rendered as 64 lowercase hex chars. */
    static String randomValue() {
        byte[] value = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(value);
        return HEX.formatHex(value);
    }

    /** The lowercase hex SHA-256 digest — the only form of a token that is ever stored. */
    static String sha256Hex(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            // Every Java platform ships SHA-256; its absence means a broken JVM.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
