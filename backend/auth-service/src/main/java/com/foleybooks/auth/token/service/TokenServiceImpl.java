package com.foleybooks.auth.token.service;

import com.foleybooks.auth.config.JwtProperties;
import com.foleybooks.auth.token.api.LoginRequest;
import com.foleybooks.auth.token.api.TokenPair;
import com.foleybooks.auth.token.domain.RefreshToken;
import com.foleybooks.auth.token.domain.RefreshTokenStatus;
import com.foleybooks.auth.token.repository.RefreshTokenRepository;
import com.foleybooks.auth.user.domain.User;
import com.foleybooks.auth.user.domain.UserStatus;
import com.foleybooks.auth.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Login's session start (FR-03, AU-17), single-use rotation with theft
 * detection for refresh sessions (FR-04, AU-16, plan §4, ADR-002) and
 * logout's row delete (FR-05, AU-18). The whole
 * refresh exchange runs as one guarded unit: the
 * predecessor row only flips ACTIVE → ROTATED through
 * {@code markRotatedIfActive}, so concurrent refreshes of the same value race
 * on the database — exactly one wins, the loser matches zero rows and is
 * treated as reuse, which is precisely what LC-22 requires ("a lost rotation
 * race is treated as reuse"). The winning path mints the successor session —
 * fresh opaque value, hash stored, ACTIVE, {@code expires_at = now + 7d} (the
 * sliding window, D-03) — and signs a 15-minute access JWT for the same user.
 * Login opens the very same kind of session after a successful credential
 * check, so both paths share {@link #startSession}.
 *
 * <p>Reuse (zero rows matched on a token whose 7-day window has not passed)
 * revokes every ACTIVE session of the account first, then answers the generic
 * 401 — re-login is required on every device (LC-08). The revocation must
 * survive that 401, which a request-scoped {@code @Transactional} boundary
 * would roll back with the thrown exception: transactions are therefore
 * programmatic per unit of work ({@link TransactionOperations}, same seam as
 * FR-01's registration race). The theft event is logged with the user's public
 * UUID and the number of revoked sessions — never a token value (C24).
 *
 * <p>Login has no such race to survive — every rejection branch is reached
 * before any write exists — so it takes the ordinary request-scoped
 * {@code @Transactional} boundary like FR-02's confirmation. Its credential
 * gate follows FR-03 in strict order: normalize the email (the boundary
 * deserializer already did; this is the defensive re-check, LC-24/LC-26),
 * verify the verbatim password against the BCrypt-12 hash, and only then look
 * at the account state. Unknown email and wrong password leave through the
 * same {@link InvalidCredentialsException}, and the unknown-email branch still
 * pays one full BCrypt verification against a throwaway hash so the response
 * time cannot whisper whether an account exists (NFR-01). An UNVERIFIED
 * account with the right password gets the actionable 403 instead (LC-05).
 */
@Service
public class TokenServiceImpl implements TokenService {

    /** Sliding refresh-session window (FR-03, FR-04, D-03): every session and rotation grants 7 fresh days. */
    static final Duration SESSION_TTL = Duration.ofDays(7);

    private static final Logger log = LoggerFactory.getLogger(TokenServiceImpl.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtIssuer jwtIssuer;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties jwtProperties;
    private final TransactionOperations transactionOperations;

    /** Throwaway BCrypt hash behind the unknown-email branch's timing parity; minted once, lazily. */
    private volatile String dummyPasswordHash;

    public TokenServiceImpl(RefreshTokenRepository refreshTokenRepository,
                            UserRepository userRepository,
                            JwtIssuer jwtIssuer,
                            PasswordEncoder passwordEncoder,
                            JwtProperties jwtProperties,
                            TransactionOperations transactionOperations) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.jwtIssuer = jwtIssuer;
        this.passwordEncoder = passwordEncoder;
        this.jwtProperties = jwtProperties;
        this.transactionOperations = transactionOperations;
    }

    @Override
    @Transactional
    public TokenPair login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        User account = userRepository.findByEmail(email).orElse(null);
        // matches() runs on every path — for an unknown address against a
        // throwaway hash — so response time never distinguishes "no such user"
        // from "wrong password" (FR-03, NFR-01). The account null-check comes
        // after, never short-circuiting the verification away.
        boolean passwordMatches = passwordEncoder.matches(
                request.password(), account != null ? account.getPasswordHash() : dummyPasswordHash());
        if (account == null || !passwordMatches) {
            throw InvalidCredentialsException.generic();
        }
        if (account.getStatus() == UserStatus.UNVERIFIED) {
            throw EmailNotVerifiedException.pendingConfirmation();
        }
        return startSession(account);
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
     * Explicit logout (FR-05, AU-18): one guarded delete keyed on the
     * SHA-256 digest of the presented value, matching only the {@code ACTIVE}
     * row — the current session dies, other devices keep theirs (LC-21), and
     * spent or revoked theft-evidence rows stay (ADR-002, see
     * {@code RefreshTokenRepository#deleteActiveByTokenHash}). The affected-row
     * count is deliberately unread: unknown and already-gone values must be
     * indistinguishable idempotent successes (LC-09), so there is exactly one
     * branch, no message, and nothing to log (C24). Runs in its own
     * transaction like login — the statement itself is atomic, and a DELETE
     * has no audit-stamp obligation (ADR-002).
     */
    @Override
    @Transactional
    public void logout(String refreshToken) {
        refreshTokenRepository.deleteActiveByTokenHash(OpaqueTokens.sha256Hex(refreshToken));
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

        return startSession(user);
    }

    /**
     * Opens a fresh session for the user — the plan §2 token pair every
     * session starts with, at login (FR-03) and at every rotation (FR-04):
     * a new opaque 256-bit value whose SHA-256 digest is minted as an ACTIVE
     * row with the full 7-day window (the raw value only ever exists in the
     * response, C24), plus a freshly signed 15-minute access JWT and the
     * user summary. Runs inside the caller's transaction — request-scoped
     * for login, the guarded rotation unit for refresh.
     */
    private TokenPair startSession(User user) {
        Instant now = Instant.now();
        String rawToken = OpaqueTokens.randomValue();
        refreshTokenRepository.saveAndFlush(
                new RefreshToken(user, OpaqueTokens.sha256Hex(rawToken), RefreshTokenStatus.ACTIVE,
                        now.plus(SESSION_TTL)));
        return new TokenPair(
                jwtIssuer.issue(user),
                rawToken,
                jwtProperties.accessTokenTtl().toSeconds(),
                new TokenPair.UserSummary(user.getId(), user.getEmail(), user.getRole()));
    }

    /** Trim + lowercase (LC-15, LC-24, LC-26); the boundary deserializer already normalized, this is the defensive re-check. */
    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * A BCrypt hash of a value that will never be presented, so the
     * unknown-email branch burns the same ~2^12 rounds as a real wrong
     * password (FR-03's "never reveal whether the account exists" also covers
     * the timing channel, NFR-01). The benign race may mint the hash twice;
     * no session can ever match it.
     */
    private String dummyPasswordHash() {
        String hash = dummyPasswordHash;
        if (hash == null) {
            hash = passwordEncoder.encode(OpaqueTokens.randomValue());
            dummyPasswordHash = hash;
        }
        return hash;
    }

    private void revokeAllSessionsAndLog(User user, Instant now) {
        int revoked = refreshTokenRepository.revokeAllActiveForUser(user.getId(), now);
        // The theft signal (FR-04, LC-08): who and what happened, never the
        // presented value or any hash of it (C24).
        log.warn("Refresh token reuse detected for user {} - revoked {} active session(s); re-login required",
                user.getId(), revoked);
    }
}
