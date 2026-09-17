package com.foleybooks.auth.token.service;

import com.foleybooks.auth.token.domain.ConfirmationToken;
import com.foleybooks.auth.token.repository.ConfirmationTokenRepository;
import com.foleybooks.auth.user.domain.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SHA-256-at-rest lifecycle for email-confirmation links.
 *
 * <p>Tokens are opaque 256-bit values (32 {@link SecureRandom} bytes rendered
 * as 64 lowercase hex chars — URL-safe for the confirmation link). Only
 * {@code sha256Hex(raw)} — also 64 lowercase hex chars — is persisted; the
 * raw value is returned once for the email and never logged. Because every
 * re-issue overwrites the single row's hash (the database keeps
 * one-per-account via {@code uk_confirmation_tokens_user}), any earlier link
 * stops resolving the moment a new one is issued.
 *
 * <p>Time is compared in UTC instants: the 24-hour TTL is minted at issue
 * time, and the 60-second resend window reads {@code last_resend_at} without
 * any extra dependency.
 */
@Service
public class ConfirmationTokenServiceImpl implements ConfirmationTokenService {

    /** A confirmation link is valid for 24 hours from issue. */
    static final Duration TOKEN_TTL = Duration.ofHours(24);

    /** At most one confirmation email per account per minute. */
    static final Duration RESEND_THROTTLE = Duration.ofSeconds(60);

    private static final int TOKEN_BYTES = 32;
    private static final HexFormat HEX = HexFormat.of();

    private final ConfirmationTokenRepository confirmationTokenRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public ConfirmationTokenServiceImpl(ConfirmationTokenRepository confirmationTokenRepository) {
        this.confirmationTokenRepository = confirmationTokenRepository;
    }

    @Override
    @Transactional
    public String issue(User user) {
        return reissue(user, false);
    }

    @Override
    @Transactional
    public String rotate(User user) {
        return reissue(user, true);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConfirmationToken> find(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return confirmationTokenRepository.findByTokenHash(sha256Hex(rawToken));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isResendThrottled(User user) {
        return confirmationTokenRepository.findByUserId(user.getId())
                .map(ConfirmationToken::getLastResendAt)
                .filter(lastResendAt -> Instant.now().isBefore(lastResendAt.plus(RESEND_THROTTLE)))
                .isPresent();
    }

    private String reissue(User user, boolean stampResend) {
        String rawToken = generateToken();
        Instant now = Instant.now();
        String tokenHash = sha256Hex(rawToken);
        Instant expiresAt = now.plus(TOKEN_TTL);

        ConfirmationToken existing = confirmationTokenRepository.findByUserId(user.getId())
                .orElse(null);
        if (existing == null) {
            ConfirmationToken created = new ConfirmationToken(user, tokenHash, expiresAt);
            if (stampResend) {
                created.markResentAt(now);
            }
            confirmationTokenRepository.save(created);
        } else {
            existing.rotateTo(tokenHash, expiresAt);
            if (stampResend) {
                existing.markResentAt(now);
            }
            confirmationTokenRepository.save(existing);
        }
        return rawToken;
    }

    private String generateToken() {
        byte[] value = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(value);
        return HEX.formatHex(value);
    }

    private static String sha256Hex(String rawToken) {
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
