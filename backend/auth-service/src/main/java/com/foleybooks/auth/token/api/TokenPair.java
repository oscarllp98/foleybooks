package com.foleybooks.auth.token.api;

import com.foleybooks.auth.user.domain.UserRole;
import java.util.UUID;

/**
 * The token pair login (FR-03) and refresh (FR-04) answer with — the plan §2
 * contract: a 15-minute RS256 access JWT, the freshly rotated opaque refresh
 * value, {@code Bearer} plus the access-token lifetime in seconds, and the
 * authenticated user summary. The new {@code refreshToken} is the ONLY place
 * the fresh raw value exists (C24); the previous one has just flipped to
 * ROTATED server-side, which is what makes its reuse the theft signal (LC-08).
 *
 * @param id    public UUID of the account (AGENTS.md §6)
 * @param email normalized account email
 * @param role  single granted role; travels in the JWT {@code roles} claim too
 */
public record TokenPair(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserSummary user) {

    /** Bearer-only API (AGENTS.md §5): the one token type ever issued. */
    public static final String BEARER = "Bearer";

    public TokenPair(String accessToken, String refreshToken, long expiresIn, UserSummary user) {
        this(accessToken, refreshToken, BEARER, expiresIn, user);
    }

    public record UserSummary(UUID id, String email, UserRole role) {
    }
}
