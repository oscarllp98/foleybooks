package com.foleybooks.auth.token.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.foleybooks.auth.common.ApiException;
import com.foleybooks.auth.config.JwtProperties;
import com.foleybooks.auth.token.api.LoginRequest;
import com.foleybooks.auth.token.api.TokenPair;
import com.foleybooks.auth.token.domain.RefreshToken;
import com.foleybooks.auth.token.domain.RefreshTokenStatus;
import com.foleybooks.auth.token.repository.RefreshTokenRepository;
import com.foleybooks.auth.user.domain.User;
import com.foleybooks.auth.user.domain.UserRole;
import com.foleybooks.auth.user.domain.UserStatus;
import com.foleybooks.auth.user.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Rotation, sliding window and reuse detection of FR-04 plus the credential
 * gate and session start of FR-03 (plan §6.1, LC-05, LC-06, LC-08, LC-22,
 * LC-24). Plain JUnit + Mockito: the guarded UPDATE results and the minted
 * session rows are asserted against the repository seam, the JWT against the
 * real AU-15 issuer, the codec is the real {@code BCryptPasswordEncoder(12)}
 * so verbatim matching and the timing-parity branch are proven, not mocked,
 * and the transaction boundary runs its callback inline (its semantics are
 * Spring's own tested behavior — the same seam choice as
 * {@code UserServiceImplTest}).
 */
class TokenServiceImplTest {

    private static final UUID USER_ID = UUID.fromString("0b8f4b2e-9d3a-4c1e-8a2b-1f2e3d4c5b6a");
    private static final UUID TOKEN_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String EMAIL = "reader@example.com";
    private static final String RAW_TOKEN = "cafebabe".repeat(8);
    private static final String STORED_TOKEN_HASH = sha256Hex(RAW_TOKEN);
    private static final String PASSWORD = "Bookworm7";

    private final RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);
    // The generic execute() rules out a lambda; running the callback inline is
    // all these unit tests need (transaction semantics are Spring's own).
    private final TransactionOperations inlineTransaction = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    };

    private RsaKeyPairProvider keyPairProvider;
    private JwtProperties jwtProperties;
    private TokenService service;
    private User user;
    private Logger logbackLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties("http://localhost:8081", Duration.ofMinutes(15), null, null);
        keyPairProvider = new RsaKeyPairProvider(jwtProperties);
        service = new TokenServiceImpl(
                repository, userRepository, new JwtIssuer(jwtProperties, keyPairProvider),
                passwordEncoder, jwtProperties, inlineTransaction);
        user = new User(EMAIL, "$2a$12$" + "x".repeat(53), UserRole.CUSTOMER, UserStatus.VERIFIED);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        logbackLogger = (Logger) LoggerFactory.getLogger(TokenServiceImpl.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(appender);
    }

    private RefreshToken storedToken(RefreshTokenStatus status, Instant expiresAt) {
        RefreshToken token = new RefreshToken(user, STORED_TOKEN_HASH, status, expiresAt);
        ReflectionTestUtils.setField(token, "id", TOKEN_ID);
        return token;
    }

    @Test
    void refresh_whenTokenActive_rotatesPredecessorAndIssuesFreshSevenDaySession() {
        Instant lapsedWindow = Instant.now().plus(1, ChronoUnit.DAYS);
        when(repository.findByTokenHash(STORED_TOKEN_HASH))
                .thenReturn(Optional.of(storedToken(RefreshTokenStatus.ACTIVE, lapsedWindow)));
        when(repository.markRotatedIfActive(eq(TOKEN_ID), any(Instant.class))).thenReturn(1);

        TokenPair pair = service.refresh(RAW_TOKEN);

        // Spent predecessor: the guarded single-use rotation write (FR-04).
        verify(repository).markRotatedIfActive(eq(TOKEN_ID), any(Instant.class));
        verify(repository, never()).revokeAllActiveForUser(any(), any());

        // Successor: fresh opaque value, ACTIVE, brand-new 7-day sliding window
        // (D-03) - notice it outlives the presented token's remaining window.
        ArgumentCaptor<RefreshToken> minted = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).saveAndFlush(minted.capture());
        RefreshToken session = minted.getValue();
        assertThat(session.getStatus()).isEqualTo(RefreshTokenStatus.ACTIVE);
        assertThat(session.getUser()).isSameAs(user);
        assertThat(session.getExpiresAt()).isAfter(lapsedWindow);
        Duration untilExpiry = Duration.between(Instant.now(), session.getExpiresAt());
        assertThat(untilExpiry).isBetween(
                TokenServiceImpl.SESSION_TTL.minusSeconds(60), TokenServiceImpl.SESSION_TTL.plusSeconds(60));
        assertThat(session.getTokenHash()).hasSize(64).isEqualTo(sha256Hex(pair.refreshToken()));

        // Response contract (plan §2): Bearer pair, 900 s, user summary.
        assertThat(pair.tokenType()).isEqualTo("Bearer");
        assertThat(pair.expiresIn()).isEqualTo(900);
        assertThat(pair.user()).isEqualTo(new TokenPair.UserSummary(USER_ID, EMAIL, UserRole.CUSTOMER));
        Claims claims = Jwts.parser()
                .verifyWith(keyPairProvider.publicKey())
                .build()
                .parseSignedClaims(pair.accessToken())
                .getPayload();
        assertThat(claims.getSubject()).isEqualTo(USER_ID.toString());
    }

    @Test
    void refresh_whenTokenResolvesToNoRow_throwsGeneric401WithoutAnyWrite() {
        when(repository.findByTokenHash(STORED_TOKEN_HASH)).thenReturn(Optional.empty());

        assertGeneric401();

        verify(repository, never()).markRotatedIfActive(any(), any());
        verify(repository, never()).revokeAllActiveForUser(any(), any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void refresh_whenSessionWindowExpired_throws401AndNeverRotatesOrRevokes() {
        // An expired-but-never-revoked session is a plain 401: no row flips to
        // ROTATED, and no account-wide revocation (that is reuse-only, LC-08).
        when(repository.findByTokenHash(STORED_TOKEN_HASH))
                .thenReturn(Optional.of(storedToken(RefreshTokenStatus.ACTIVE, Instant.now().minusSeconds(1))));

        assertGeneric401();

        verify(repository, never()).markRotatedIfActive(any(), any());
        verify(repository, never()).revokeAllActiveForUser(any(), any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void refresh_rotatedTokenRevokesAllSessions() {
        // Plan §6.1 oracle (FR-04, LC-08, LC-22): presenting a spent value -
        // theft or a lost rotation race - flips ACTIVE → REVOKED for every
        // session of the account before the generic 401.
        when(repository.findByTokenHash(STORED_TOKEN_HASH))
                .thenReturn(Optional.of(storedToken(RefreshTokenStatus.ROTATED, Instant.now().plus(6, ChronoUnit.DAYS))));
        when(repository.markRotatedIfActive(eq(TOKEN_ID), any(Instant.class))).thenReturn(0);
        when(repository.revokeAllActiveForUser(eq(USER_ID), any(Instant.class))).thenReturn(2);

        assertGeneric401();

        verify(repository).revokeAllActiveForUser(eq(USER_ID), any(Instant.class));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void refresh_whenReuseDetected_logsEventWithoutAnyTokenValue() {
        when(repository.findByTokenHash(STORED_TOKEN_HASH))
                .thenReturn(Optional.of(storedToken(RefreshTokenStatus.ROTATED, Instant.now().plus(6, ChronoUnit.DAYS))));
        when(repository.markRotatedIfActive(eq(TOKEN_ID), any(Instant.class))).thenReturn(0);
        when(repository.revokeAllActiveForUser(eq(USER_ID), any(Instant.class))).thenReturn(2);

        assertGeneric401();

        List<String> reuseEvents = appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(reuseEvents).singleElement()
                .satisfies(event -> assertThat(event)
                        // Who and what happened (public UUID, counts) ...
                        .contains(USER_ID.toString())
                        .contains("2 active session(s)")
                        // ... never the presented value or its digest (C24).
                        .doesNotContain(RAW_TOKEN)
                        .doesNotContain(STORED_TOKEN_HASH));
    }

    // ---------------------------------------------------------------- AU-17: login (FR-03)

    @Test
    void login_validCredentials_returnsTokenPairAndOpensActiveSevenDaySession() {
        User account = verifiedAccountWithPassword(PASSWORD);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));

        TokenPair pair = service.login(new LoginRequest(EMAIL, PASSWORD));

        // A brand-new session row, not a rotation: ACTIVE, hash-of-fresh-value,
        // the full 7-day window from right now (FR-03, D-03).
        ArgumentCaptor<RefreshToken> minted = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).saveAndFlush(minted.capture());
        RefreshToken session = minted.getValue();
        assertThat(session.getStatus()).isEqualTo(RefreshTokenStatus.ACTIVE);
        assertThat(session.getUser()).isSameAs(account);
        assertThat(session.getTokenHash()).hasSize(64).isEqualTo(sha256Hex(pair.refreshToken()));
        Duration untilExpiry = Duration.between(Instant.now(), session.getExpiresAt());
        assertThat(untilExpiry).isBetween(
                TokenServiceImpl.SESSION_TTL.minusSeconds(60), TokenServiceImpl.SESSION_TTL.plusSeconds(60));
        verify(repository, never()).markRotatedIfActive(any(), any());

        // The plan §2 envelope, identical in shape to a refresh rotation.
        assertThat(pair.tokenType()).isEqualTo("Bearer");
        assertThat(pair.expiresIn()).isEqualTo(900);
        assertThat(pair.user()).isEqualTo(new TokenPair.UserSummary(USER_ID, EMAIL, UserRole.CUSTOMER));
        Claims claims = Jwts.parser()
                .verifyWith(keyPairProvider.publicKey())
                .build()
                .parseSignedClaims(pair.accessToken())
                .getPayload();
        assertThat(claims.getSubject()).isEqualTo(USER_ID.toString());
        assertThat(claims.get("email", String.class)).isEqualTo(EMAIL);
        assertThat(claims.get("roles", List.class)).containsExactly("CUSTOMER");
    }

    @Test
    void login_mixedCaseAndWhitespaceEmail_normalizesButPasswordStaysVerbatim() {
        // Plan §6.1 oracle (LC-24, LC-26): the lookup sees the canonical
        // address, the codec sees the password untouched, spaces and all.
        verifiedAccountWithPassword(" Bookworm7 ");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        TokenPair pair = service.login(new LoginRequest("  Reader@Example.COM  ", " Bookworm7 "));

        verify(userRepository).findByEmail(EMAIL);
        assertThat(pair.user().email()).isEqualTo(EMAIL);
    }

    @Test
    void login_trimmedVariantOfPaddedPassword_throwsGeneric401() {
        // LC-26 the other way round: whitespace is significant at login too —
        // the stored " Bookworm7 " hash must NOT accept "Bookworm7".
        verifiedAccountWithPassword(" Bookworm7 ");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        assertInvalidCredentials();

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void login_unknownEmail_throwsGeneric401AndNeverMintsSession() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertInvalidCredentials();

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void login_wrongPassword_throws401FieldIdenticalToUnknownEmail() {
        // LC-06: repeated wrong passwords — and the unknown-address branch —
        // all leave through the exact same exception: same status, URN, title,
        // detail and properties. Nothing can tell the two apart (NFR-01).
        when(userRepository.findByEmail(EMAIL))
                .thenReturn(Optional.of(verifiedAccountWithPassword("Otherpass1")));
        InvalidCredentialsException wrongPassword = catchThrowableOfType(
                () -> service.login(new LoginRequest(EMAIL, PASSWORD)), InvalidCredentialsException.class);

        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        InvalidCredentialsException unknownEmail = catchThrowableOfType(
                () -> service.login(new LoginRequest(EMAIL, PASSWORD)), InvalidCredentialsException.class);

        assertThat(wrongPassword).extracting(
                ApiException::getStatus, ApiException::getType, ApiException::getTitle,
                ApiException::getDetail, ApiException::getProperties)
                .isEqualTo(List.of(unknownEmail.getStatus(), unknownEmail.getType(), unknownEmail.getTitle(),
                        unknownEmail.getDetail(), unknownEmail.getProperties()));
    }

    @Test
    void login_unknownEmail_stillRunsOneBcryptVerificationToKeepTimingUniform() {
        // FR-03's "never reveal whether the account exists" also covers the
        // timing channel: the no-row branch verifies against a throwaway hash
        // so an unknown address costs the same BCrypt work as a wrong password.
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("$2a$12$" + "d".repeat(53));
        when(encoder.matches(anyString(), anyString())).thenReturn(false);
        TokenService local = new TokenServiceImpl(
                repository, userRepository, new JwtIssuer(jwtProperties, keyPairProvider),
                encoder, jwtProperties, inlineTransaction);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> local.login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(encoder).matches(PASSWORD, "$2a$12$" + "d".repeat(53));
    }

    @Test
    void login_unverifiedAccountWithCorrectPassword_throws403ConfirmYourEmail() {
        // LC-05: actionable rejection — reachable only after the password
        // matched, and it mints no session.
        verifiedAccountWithPassword(PASSWORD);
        user = new User(EMAIL, user.getPasswordHash(), UserRole.CUSTOMER, UserStatus.UNVERIFIED);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOf(EmailNotVerifiedException.class)
                .extracting(ex -> ((ApiException) ex).getStatus().value())
                .isEqualTo(403);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void login_unverifiedAccountWithWrongPassword_throwsGeneric401NotForbidden() {
        // The 403 must never fire on a failed password, or login itself
        // becomes an account-existence oracle (FR-03, LC-06 vs LC-05 order).
        verifiedAccountWithPassword(PASSWORD);
        user = new User(EMAIL, user.getPasswordHash(), UserRole.CUSTOMER, UserStatus.UNVERIFIED);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, "Wrongpass1")))
                .isInstanceOf(InvalidCredentialsException.class)
                .extracting(ex -> ((ApiException) ex).getStatus().value())
                .isEqualTo(401);

        verify(repository, never()).saveAndFlush(any());
    }

    /** Stands up the shared user with a real BCrypt-12 hash of {@code password} and returns it. */
    private User verifiedAccountWithPassword(String password) {
        user = new User(EMAIL, passwordEncoder.encode(password), UserRole.CUSTOMER, UserStatus.VERIFIED);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }

    private void assertInvalidCredentials() {
        assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOf(InvalidCredentialsException.class)
                .extracting(ex -> ((ApiException) ex).getStatus().value())
                .isEqualTo(401);
    }

    private void assertGeneric401() {
        assertThatThrownBy(() -> service.refresh(RAW_TOKEN))
                .isInstanceOf(RefreshTokenException.class)
                .extracting(ex -> ((RefreshTokenException) ex).getStatus().value())
                .isEqualTo(401);
    }

    /** Test-local oracle for the SHA-256 lowercase-hex lookup contract. */
    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
