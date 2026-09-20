package com.foleybooks.auth.token.repository;

import com.foleybooks.auth.token.domain.RefreshToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Lookups and atomic status writes for auth_db {@code refresh_tokens}
 * (ADR-002). The hash unique constraint doubles as the refresh/logout lookup
 * path; both bulk updates guard on {@code status} so rotation and
 * reuse-detection are race-safe, and stamp {@code updated_at} explicitly
 * because {@code @UpdateTimestamp} never fires on bulk statements (ADR-002).
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * The explicit-logout row delete (FR-05, D-05, AU-18): only the
     * {@code ACTIVE} session the presented value belongs to disappears — the
     * current session only, other devices keep their rows (LC-21), and every
     * later refresh with the value misses the lookup. The status guard is
     * load-bearing: spent {@code ROTATED} and theft-{@code REVOKED} rows are
     * precisely the reuse-detection evidence LC-08 depends on (ADR-002), so
     * logging out with an old value must not be able to erase it — matching
     * zero rows is idempotent success (LC-09), which the service renders as
     * the same 204 either way.
     */
    @Modifying
    @Query("""
            DELETE FROM RefreshToken t
             WHERE t.tokenHash = :tokenHash
               AND t.status = com.foleybooks.auth.token.domain.RefreshTokenStatus.ACTIVE
            """)
    int deleteActiveByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * The single-use rotation write (FR-04, LC-22): one UPDATE that only flips
     * an {@code ACTIVE} row, so of two concurrent refreshes with the same
     * token exactly one wins and the loser matches zero rows — the same
     * guarded-UPDATE pattern as LC-23. Returns the affected-row count
     * (1 = this call rotated the session, 0 = spent token or lost race).
     */
    @Modifying
    @Query("""
            UPDATE RefreshToken t
               SET t.status = com.foleybooks.auth.token.domain.RefreshTokenStatus.ROTATED,
                   t.updatedAt = :rotatedAt
             WHERE t.id = :tokenId
               AND t.status = com.foleybooks.auth.token.domain.RefreshTokenStatus.ACTIVE
            """)
    int markRotatedIfActive(@Param("tokenId") UUID tokenId, @Param("rotatedAt") Instant rotatedAt);

    /**
     * Reuse-detection revocation (FR-04, LC-08): every still-{@code ACTIVE}
     * session of the account flips to {@code REVOKED} — re-login is then
     * required on every device. The presented spent row is untouched (it is
     * not ACTIVE); rotated predecessors stay (ADR-002). Returns the number of
     * sessions revoked.
     */
    @Modifying
    @Query("""
            UPDATE RefreshToken t
               SET t.status = com.foleybooks.auth.token.domain.RefreshTokenStatus.REVOKED,
                   t.updatedAt = :revokedAt
             WHERE t.user.id = :userId
               AND t.status = com.foleybooks.auth.token.domain.RefreshTokenStatus.ACTIVE
            """)
    int revokeAllActiveForUser(@Param("userId") UUID userId, @Param("revokedAt") Instant revokedAt);
}
