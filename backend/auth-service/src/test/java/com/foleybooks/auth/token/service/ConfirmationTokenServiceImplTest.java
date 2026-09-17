package com.foleybooks.auth.token.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.foleybooks.auth.token.domain.ConfirmationToken;
import com.foleybooks.auth.token.repository.ConfirmationTokenRepository;
import com.foleybooks.auth.user.domain.User;
import com.foleybooks.auth.user.domain.UserRole;
import com.foleybooks.auth.user.domain.UserStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Plain JUnit + Mockito unit tests for the confirmation-token lifecycle:
 * SHA-256 at rest, 24-hour TTL, one active link per account, and the
 * {@code last_resend_at} throttle window. The repository is mocked —
 * persistence itself is proven by {@code ConfirmationTokenRepositoryTest}.
 */
class ConfirmationTokenServiceImplTest {

    private static final Duration TTL = Duration.ofHours(24);
    private static final Duration THROTTLE = Duration.ofSeconds(60);
    private static final UUID USER_ID = UUID.fromString("0b8f4b2e-9d3a-4c1e-8a2b-1f2e3d4c5b6a");

    private final ConfirmationTokenRepository repository = mock(ConfirmationTokenRepository.class);
    private final ConfirmationTokenService service = new ConfirmationTokenServiceImpl(repository);

    private User user;

    @BeforeEach
    void setUp() {
        user = new User("reader@example.com", "$2a$12$" + "x".repeat(53), UserRole.CUSTOMER,
                UserStatus.UNVERIFIED);
        ReflectionTestUtils.setField(user, "id", USER_ID);
    }

    @Test
    void issue_whenNoActiveToken_persistsSha256DigestOfReturnedValue() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        Instant before = Instant.now();

        String rawToken = service.issue(user);

        ConfirmationToken saved = captureSaved();
        assertThat(sha256Hex(rawToken)).isEqualTo(saved.getTokenHash());
        // The raw value never touches the stored row, and both are 64 lowercase hex.
        assertThat(saved.getTokenHash()).isNotEqualTo(rawToken).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(rawToken).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getExpiresAt()).isBetween(before.plus(TTL).minusSeconds(5), Instant.now().plus(TTL).plusSeconds(5));
    }

    @Test
    void issue_whenNoActiveToken_leavesResendThrottleUnstamped() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        service.issue(user);

        // The throttle clock starts at the first resend, not at issuance.
        assertThat(captureSaved().getLastResendAt()).isNull();
    }

    @Test
    void issue_whenRowAlreadyExists_replacesHashInPlaceKeepingSingleRow() {
        ConfirmationToken existing = storedRow("a".repeat(64), Instant.now().plus(23, ChronoUnit.HOURS));
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));

        String rawToken = service.issue(user);

        ConfirmationToken saved = captureSaved();
        assertThat(saved).isSameAs(existing); // same row updated, never a second insert
        assertThat(saved.getTokenHash()).isEqualTo(sha256Hex(rawToken)).isNotEqualTo("a".repeat(64));
    }

    @Test
    void issue_whenCalledTwice_returnsDifferentValues() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        String first = service.issue(user);
        String second = service.issue(user);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void rotate_whenRowExists_invalidatesPreviousLinkAndStampsThrottle() {
        String previousRaw = "deadbeef".repeat(8);
        ConfirmationToken existing = storedRow(sha256Hex(previousRaw), Instant.now().plus(23, ChronoUnit.HOURS));
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
        Instant before = Instant.now();

        String rawToken = service.rotate(user);

        ConfirmationToken saved = captureSaved();
        assertThat(saved).isSameAs(existing);
        assertThat(saved.getTokenHash()).isEqualTo(sha256Hex(rawToken)).isNotEqualTo(sha256Hex(previousRaw));
        assertThat(saved.getExpiresAt()).isBetween(before.plus(TTL).minusSeconds(5), Instant.now().plus(TTL).plusSeconds(5));
        assertThat(saved.getLastResendAt()).isBetween(before.minusSeconds(5), Instant.now().plusSeconds(5));
    }

    @Test
    void rotate_whenNoRow_createsFreshStampedRow() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        Instant before = Instant.now();

        String rawToken = service.rotate(user);

        ConfirmationToken saved = captureSaved();
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getTokenHash()).isEqualTo(sha256Hex(rawToken));
        assertThat(saved.getLastResendAt()).isBetween(before.minusSeconds(5), Instant.now().plusSeconds(5));
    }

    @Test
    void find_whenTokenStored_looksUpBySha256Digest() {
        String rawToken = "cafebabe".repeat(8);
        ConfirmationToken row = storedRow(sha256Hex(rawToken), Instant.now().plus(1, ChronoUnit.HOURS));
        when(repository.findByTokenHash(sha256Hex(rawToken))).thenReturn(Optional.of(row));

        Optional<ConfirmationToken> found = service.find(rawToken);

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(repository).findByTokenHash(hash.capture());
        assertThat(hash.getValue()).isEqualTo(sha256Hex(rawToken));
        assertThat(found).contains(row);
    }

    @Test
    void find_whenRawTokenIsKnownVector_resolvesDocumentedDigest() {
        // Independent oracle: SHA-256("abc") is the canonical published digest.
        String knownHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        ConfirmationToken row = storedRow(knownHash, Instant.now().plus(1, ChronoUnit.HOURS));
        when(repository.findByTokenHash(knownHash)).thenReturn(Optional.of(row));

        assertThat(service.find("abc")).contains(row);
    }

    @Test
    void find_whenNoRowMatches_returnsEmpty() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThat(service.find("0".repeat(64))).isEmpty();
    }

    @Test
    void find_whenRawTokenBlankOrNull_returnsEmptyWithoutLookup() {
        assertThat(service.find(null)).isEmpty();
        assertThat(service.find("")).isEmpty();
        assertThat(service.find("   ")).isEmpty();

        verifyNoInteractions(repository);
    }

    @Test
    void isResendThrottled_whenLastResendBelowSixtySecondsAgo_returnsTrue() {
        storedAndStubRow(Instant.now().minusSeconds(30));

        assertThat(service.isResendThrottled(user)).isTrue();
    }

    @Test
    void isResendThrottled_whenLastResendOverSixtySecondsAgo_returnsFalse() {
        storedAndStubRow(Instant.now().minus(THROTTLE).minusSeconds(5));

        assertThat(service.isResendThrottled(user)).isFalse();
    }

    @Test
    void isResendThrottled_whenNeverResent_returnsFalse() {
        storedAndStubRow(null);

        assertThat(service.isResendThrottled(user)).isFalse();
    }

    @Test
    void isResendThrottled_whenNoRow_returnsFalse() {
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThat(service.isResendThrottled(user)).isFalse();
    }

    private void storedAndStubRow(Instant lastResendAt) {
        ConfirmationToken row = storedRow("f".repeat(64), Instant.now().plus(1, ChronoUnit.HOURS));
        if (lastResendAt != null) {
            row.markResentAt(lastResendAt);
        }
        when(repository.findByUserId(USER_ID)).thenReturn(Optional.of(row));
    }

    private ConfirmationToken storedRow(String tokenHash, Instant expiresAt) {
        return new ConfirmationToken(user, tokenHash, expiresAt);
    }

    private ConfirmationToken captureSaved() {
        ArgumentCaptor<ConfirmationToken> captor = ArgumentCaptor.forClass(ConfirmationToken.class);
        verify(repository).save(captor.capture());
        verify(repository, never()).delete(any());
        return captor.getValue();
    }

    /** Test-local oracle for the SHA-256 lowercase-hex contract. */
    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
