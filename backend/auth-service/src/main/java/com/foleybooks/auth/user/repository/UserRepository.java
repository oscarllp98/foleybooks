package com.foleybooks.auth.user.repository;

import com.foleybooks.auth.user.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Lookups for auth_db {@code users} (ADR-002). {@code findByEmail} is the
 * register/login path against the normalized email; {@code uk_users_email}
 * makes the concurrent-registration race (LC-19) safe — exactly one INSERT
 * wins, the loser re-reads.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);
}
