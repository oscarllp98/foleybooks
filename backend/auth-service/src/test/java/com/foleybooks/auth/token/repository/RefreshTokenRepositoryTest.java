package com.foleybooks.auth.token.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foleybooks.auth.token.domain.RefreshToken;
import com.foleybooks.auth.token.domain.RefreshTokenStatus;
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
 * Hash lookups and hash uniqueness on refresh_tokens (plan §6.3, ADR-002)
 * against real PostgreSQL (C15).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@TestPropertySource(properties = "AUTH_DB_PASSWORD=test-db-secret")
class RefreshTokenRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String BCRYPT_12_DUMMY = "$2a$12$" + "x".repeat(53);
    private static final String SHA_256_HEX = "0123456789abcdef".repeat(4);

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @Autowired
    UserRepository userRepository;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.saveAndFlush(new User(
                "rt-owner@foleybooks.com", BCRYPT_12_DUMMY, UserRole.CUSTOMER, UserStatus.VERIFIED));
    }

    @Test
    void findByTokenHash_whenTokenStored_returnsMatchingToken() {
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        refreshTokenRepository.saveAndFlush(
                new RefreshToken(user, SHA_256_HEX, RefreshTokenStatus.ACTIVE, expiresAt));

        RefreshToken found = refreshTokenRepository.findByTokenHash(SHA_256_HEX).orElseThrow();
        assertThat(found.getId()).isNotNull();
        assertThat(found.getTokenHash()).isEqualTo(SHA_256_HEX);
        assertThat(found.getStatus()).isEqualTo(RefreshTokenStatus.ACTIVE);
        // Postgres stores microseconds; nanoseconds are truncated on round-trip.
        assertThat(Duration.between(expiresAt, found.getExpiresAt()).abs().toMillis()).isZero();
        assertThat(found.getUser().getEmail()).isEqualTo("rt-owner@foleybooks.com");
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void save_whenTokenHashAlreadyExists_throwsDataIntegrityViolation() {
        // uk_refresh_tokens_hash: the single lookup path must stay unambiguous.
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        refreshTokenRepository.saveAndFlush(
                new RefreshToken(user, SHA_256_HEX, RefreshTokenStatus.ACTIVE, expiresAt));

        User other = userRepository.saveAndFlush(new User(
                "rt-other@foleybooks.com", BCRYPT_12_DUMMY, UserRole.CUSTOMER, UserStatus.VERIFIED));

        assertThatThrownBy(() -> refreshTokenRepository.saveAndFlush(
                new RefreshToken(other, SHA_256_HEX, RefreshTokenStatus.ACTIVE, expiresAt)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByTokenHash_whenUnknownHash_returnsEmpty() {
        assertThat(refreshTokenRepository.findByTokenHash("f".repeat(64))).isEmpty();
    }
}
