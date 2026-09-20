package com.foleybooks.auth.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foleybooks.auth.user.domain.User;
import com.foleybooks.auth.user.domain.UserRole;
import com.foleybooks.auth.user.domain.UserStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL via Testcontainers (C15); Flyway applies V1+V2 and
 * {@code ddl-auto=validate} (from application.yml) proves the entities match
 * the migrated schema (C16, C17).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = "AUTH_DB_PASSWORD=test-db-secret")
class UserRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String BCRYPT_12_DUMMY = "$2a$12$" + "x".repeat(53);

    @Autowired
    UserRepository userRepository;

    @Autowired
    TestEntityManager entityManager;

    @Test
    void seedDemoUsers_whenMigrationsApplied_hashesMatchDocumentedDemoPasswords() {
        // FR-14: fixed-UUID demo accounts, VERIFIED, per ADR-002 / README credentials.
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

        User admin = userRepository.findByEmail("admin@foleybooks.com").orElseThrow();
        assertThat(admin.getId()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(admin.getPasswordHash()).hasSize(60);
        assertThat(admin.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(admin.getStatus()).isEqualTo(UserStatus.VERIFIED);
        assertThat(admin.getCreatedAt()).isNotNull();
        assertThat(admin.getUpdatedAt()).isNotNull();
        assertThat(encoder.matches("Admin@1234", admin.getPasswordHash())).isTrue();

        User customer = userRepository.findByEmail("customer@foleybooks.com").orElseThrow();
        assertThat(customer.getId()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        assertThat(customer.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(customer.getStatus()).isEqualTo(UserStatus.VERIFIED);
        assertThat(customer.getCreatedAt()).isNotNull();
        assertThat(customer.getUpdatedAt()).isNotNull();
        assertThat(encoder.matches("Customer@1234", customer.getPasswordHash())).isTrue();
    }

    @Test
    void save_whenEmailAlreadyExists_throwsDataIntegrityViolation() {
        // uk_users_email: the race-safety invariant behind LC-19.
        userRepository.saveAndFlush(
                new User("dupe@foleybooks.com", BCRYPT_12_DUMMY, UserRole.CUSTOMER, UserStatus.UNVERIFIED));

        assertThatThrownBy(() -> userRepository.saveAndFlush(new User(
                "dupe@foleybooks.com", BCRYPT_12_DUMMY, UserRole.CUSTOMER, UserStatus.UNVERIFIED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_whenEmailNotLowercase_throwsDataIntegrityViolation() {
        // ck_users_email_lower at-rest backstop (ADR-002): service normalization
        // (LC-15) is mandatory, non-lowercase data cannot be persisted anyway.
        assertThatThrownBy(() -> userRepository.saveAndFlush(new User(
                "NotLower@FooleyBooks.com", BCRYPT_12_DUMMY, UserRole.CUSTOMER, UserStatus.UNVERIFIED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByEmail_whenNoAccountMatches_returnsEmpty() {
        assertThat(userRepository.findByEmail("ghost@foleybooks.com")).isEmpty();
    }

    @Test
    void verifyIfUnverified_whenAccountUnverified_flipsStatusAndStampsUpdatedAt() {
        // AU-13 / ADR-002: FR-02's verification is one guarded bulk UPDATE, and
        // bulk SQL bypasses @UpdateTimestamp — the statement must carry the
        // audit stamp itself (AGENTS.md §7).
        User unverified = userRepository.saveAndFlush(new User(
                "confirm-me@foleybooks.com", BCRYPT_12_DUMMY, UserRole.CUSTOMER, UserStatus.UNVERIFIED));
        Instant verifiedAt = Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(1);

        assertThat(userRepository.verifyIfUnverified(unverified.getId(), verifiedAt)).isEqualTo(1);

        entityManager.flush();
        entityManager.clear();
        User stored = userRepository.findById(unverified.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(UserStatus.VERIFIED);
        assertThat(stored.getUpdatedAt()).isEqualTo(verifiedAt);
    }

    @Test
    void verifyIfUnverified_whenAccountAlreadyVerified_matchesNoRowAndLeavesAuditUntouched() {
        // LC-23 at the database: the second confirmation's UPDATE predicate
        // finds no UNVERIFIED row, affects nothing, and must not rewrite the
        // audit trail — the service turns this zero into the idempotent 200.
        User verified = userRepository.saveAndFlush(new User(
                "already-confirmed@foleybooks.com", BCRYPT_12_DUMMY, UserRole.CUSTOMER, UserStatus.VERIFIED));
        Instant originalUpdatedAt = verified.getUpdatedAt();

        assertThat(userRepository.verifyIfUnverified(
                verified.getId(), originalUpdatedAt.plus(1, ChronoUnit.HOURS))).isZero();

        entityManager.flush();
        entityManager.clear();
        User stored = userRepository.findById(verified.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(UserStatus.VERIFIED);
        assertThat(Duration.between(originalUpdatedAt, stored.getUpdatedAt()).abs().toMillis()).isZero();
    }
}
