package com.foleybooks.auth.token.service;

import com.foleybooks.auth.config.JwtProperties;
import com.foleybooks.auth.token.api.TokenPair;
import com.foleybooks.auth.token.domain.RefreshToken;
import com.foleybooks.auth.token.domain.RefreshTokenStatus;
import com.foleybooks.auth.token.repository.RefreshTokenRepository;
import com.foleybooks.auth.user.domain.User;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Single-use rotation with theft detection for refresh sessions (FR-04,
 * AU-16, plan §4, ADR-002). The whole exchange runs as one guarded unit: the
 * predecessor row only flips ACTIVE → ROTATED through
 * {@code markRotatedIfActive}, so concurrent refreshes of the same value race
 * on the database — exactly one wins, the loser matches zero rows and is
 * treated as reuse, which is precisely what LC-22 requires ("a lost rotation
 * race is treated as reuse"). The winning path mints the successor session —
 * fresh opaque value, hash stored, ACTIVE, {@code expires_at = now + 7d} (the
 * sliding window, D-03) — and signs a 15-minute access JWT for the same user.
 *
 * <p>Reuse (zero rows matched on a token whose 7-day window has not passed)
 * revokes every ACTIVE session of the account first, then answers the generic
 * 401 — re-login is required on every device (LC-08). The revocation must
 * survive that 401, which a request-scoped {@code @Transactional} boundary
 * would roll back with the thrown exception: transactions are therefore
 * programmatic per unit of work ({@link TransactionOperations}, same seam as
 * FR-01's registration race). The theft event is logged with the user's public
 * UUID and the number of revoked sessions — never a token value (C24).
 */
@Service
public class TokenServiceImpl implements TokenService {

    /** Sliding refresh-session window (FR-04, D-03): every rotation grants 7 fresh days. */
    static final Duration SESSION_TTL = Duration.ofDays(7);

    private static final Logger log = LoggerFactory.getLogger(TokenServiceImpl.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtIssuer jwtIssuer;
    private final JwtProperties jwtProperties;
    private final TransactionOperations transactionOperations;

    public TokenServiceImpl(RefreshTokenRepository refreshTokenRepository,
                            JwtIssuer jwtIssuer,
                            JwtProperties jwtProperties,
                            TransactionOperations transactionOperations) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtIssuer = jwtIssuer;
        this.jwtProperties = jwtProperties;
        this.transactionOperations = transactionOperations;
    }

    @Override
    public TokenPair refresh(String refreshToken) {
        TokenPair rotated = transactionOperations.execute(status -> rotateWithinTransaction(refreshToken));
        if (rotated == null) {
            // Unknown, expired or reused-and-now-revoked — the single generic
            // answer (FR-04). Any revocation already committed with the unit.
            throw RefreshTokenException.invalidOrExpired();
        }
        return rotated;
    }

    /**
     * One transaction: lookup, guarded rotation, successor insert, JWT.
     * Returns {@code null} for every rejection branch — including reuse,
     * whose revoke-all has just been written here and commits on return,
     * deliberately outside the exception path (see class javadoc).
     */
    private TokenPair rotateWithinTransaction(String refreshToken) {
        RefreshToken spent = refreshTokenRepository
                .findByTokenHash(OpaqueTokens.sha256Hex(refreshToken))
                .orElse(null);
        if (spent == null) {
            return null;
        }
        Instant now = Instant.now();
        if (!spent.getExpiresAt().isAfter(now)) {
            return null;
        }

        User user = spent.getUser();
        if (refreshTokenRepository.markRotatedIfActive(spent.getId(), now) == 0) {
            revokeAllSessionsAndLog(user, now);
            return null;
        }

        String rawSuccessor = OpaqueTokens.randomValue();
        refreshTokenRepository.saveAndFlush(
                new RefreshToken(user, OpaqueTokens.sha256Hex(rawSuccessor), RefreshTokenStatus.ACTIVE,
                        now.plus(SESSION_TTL)));
        return new TokenPair(
                jwtIssuer.issue(user),
                rawSuccessor,
                jwtProperties.accessTokenTtl().toSeconds(),
                new TokenPair.UserSummary(user.getId(), user.getEmail(), user.getRole()));
    }

    private void revokeAllSessionsAndLog(User user, Instant now) {
        int revoked = refreshTokenRepository.revokeAllActiveForUser(user.getId(), now);
        // The theft signal (FR-04, LC-08): who and what happened, never the
        // presented value or any hash of it (C24).
        log.warn("Refresh token reuse detected for user {} - revoked {} active session(s); re-login required",
                user.getId(), revoked);
    }
}
