package com.foleybooks.auth.token.repository;

import com.foleybooks.auth.token.domain.RefreshToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Lookups for auth_db {@code refresh_tokens} (ADR-002). The hash unique
 * constraint doubles as the refresh/logout lookup path; bulk revoke-all is
 * added with AU-16 (reuse detection, LC-08).
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);
}
