package com.foleybooks.auth.token.repository;

import com.foleybooks.auth.token.domain.ConfirmationToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Lookups for auth_db {@code confirmation_tokens} (ADR-002). Hash lookup
 * resolves the confirm link; "one active link per account" is guaranteed by
 * {@code uk_confirmation_tokens_user} (FR-02).
 */
public interface ConfirmationTokenRepository extends JpaRepository<ConfirmationToken, UUID> {

    Optional<ConfirmationToken> findByTokenHash(String tokenHash);
}
