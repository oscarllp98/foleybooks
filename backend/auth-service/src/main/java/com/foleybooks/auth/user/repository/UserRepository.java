package com.foleybooks.auth.user.repository;

import com.foleybooks.auth.user.domain.User;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Lookups for auth_db {@code users} (ADR-002). {@code findByEmail} is the
 * register/login path against the normalized email; {@code uk_users_email}
 * makes the concurrent-registration race (LC-19) safe — exactly one INSERT
 * wins, the loser re-reads.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    /**
     * The atomic verification write (FR-02, LC-23): a single UPDATE the
     * database only applies while the account is still UNVERIFIED, so
     * concurrent double-use of one confirmation link can flip the status at
     * most once — Postgres row-locks the second caller against the first, then
     * matches zero rows. Returns the affected-row count (1 = this call
     * verified the account, 0 = someone else already had). {@code updated_at}
     * is set explicitly because {@code @UpdateTimestamp} only fires on entity
     * saves, never on bulk statements (ADR-002).
     */
    @Modifying
    @Query("""
            UPDATE User u
               SET u.status = com.foleybooks.auth.user.domain.UserStatus.VERIFIED,
                   u.updatedAt = :verifiedAt
             WHERE u.id = :userId
               AND u.status = com.foleybooks.auth.user.domain.UserStatus.UNVERIFIED
            """)
    int verifyIfUnverified(@Param("userId") UUID userId, @Param("verifiedAt") Instant verifiedAt);
}
