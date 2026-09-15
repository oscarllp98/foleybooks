package com.foleybooks.auth.token.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foleybooks.auth.token.domain.ConfirmationToken;
import com.foleybooks.auth.user.domain.User;
import com.foleybooks.auth.user.domain.UserRole;
import com.foleybooks.auth.user.domain.UserStatus;
import com.foleybooks.auth.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Hash lookups and the single-row-per-user invariant on confirmation_tokens
 * (plan §6.3, FR-02, ADR-002) against real PostgreSQL (C15).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = "AUTH_DB_PASSWORD=test-db-secret")
class ConfirmationTokenRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String BCRYPT_12_DUMMY = "$2a$12$" + "x".repeat(53);
    private static final String TOKEN_HASH = "0123456789abcdef".repeat(4);
    private static final String OTHER_TOKEN_HASH = "fedcba9876543210".repeat(4);

    @Autowired
    ConfirmationTokenRepository confirmationTokenRepository;

    @Autowired
    UserRepository userRepository;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.saveAndFlush(new User(
                "ct-owner@foleybooks.com", BCRYPT_12_DUMMY, UserRole.CUSTOMER, UserStatus.UNVERIFIED));
    }

    @Test
    void findByTokenHash_whenTokenStored_returnsMatchingToken() {
        Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);
        confirmationTokenRepository.saveAndFlush(new ConfirmationToken(user, TOKEN_HASH, expiresAt));

        ConfirmationToken found = confirmationTokenRepository.findByTokenHash(TOKEN_HASH).orElseThrow();
        assertThat(found.getId()).isNotNull();
        assertThat(found.getTokenHash()).isEqualTo(TOKEN_HASH);
        // Postgres stores microseconds; nanoseconds are truncated on round-trip.
        assertThat(Duration.between(expiresAt, found.getExpiresAt()).abs().toMillis()).isZero();
        assertThat(found.getLastResendAt()).isNull();
        assertThat(found.getUser().getEmail()).isEqualTo("ct-owner@foleybooks.com");
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void save_whenSecondActiveTokenForSameUser_throwsDataIntegrityViolation() {
        // uk_confirmation_tokens_user: at most one active link per account (FR-02).
        Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);
        confirmationTokenRepository.saveAndFlush(new ConfirmationToken(user, TOKEN_HASH, expiresAt));

        assertThatThrownBy(() -> confirmationTokenRepository.saveAndFlush(
                new ConfirmationToken(user, OTHER_TOKEN_HASH, expiresAt)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByTokenHash_whenUnknownHash_returnsEmpty() {
        assertThat(confirmationTokenRepository.findByTokenHash("e".repeat(64))).isEmpty();
    }
}
