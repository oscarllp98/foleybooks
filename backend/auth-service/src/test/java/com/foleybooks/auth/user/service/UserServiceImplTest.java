package com.foleybooks.auth.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foleybooks.auth.config.FrontendProperties;
import com.foleybooks.auth.mail.MailSender;
import com.foleybooks.auth.token.domain.ConfirmationToken;
import com.foleybooks.auth.token.service.ConfirmationTokenService;
import com.foleybooks.auth.user.api.ConfirmRequest;
import com.foleybooks.auth.user.api.ConfirmResponse;
import com.foleybooks.auth.user.api.RegisterRequest;
import com.foleybooks.auth.user.api.RegisterResponse;
import com.foleybooks.auth.user.domain.User;
import com.foleybooks.auth.user.domain.UserRole;
import com.foleybooks.auth.user.domain.UserStatus;
import com.foleybooks.auth.user.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Plain JUnit + Mockito unit tests for FR-01 registration and FR-02
 * confirmation (plan §6.1). The password codec is the real
 * {@code BCryptPasswordEncoder(12)} so the "BCrypt strength 12" claim is
 * proven, not mocked; the transaction template runs its callback inline (the
 * boundary itself is Spring's job), while repositories and the mail port are
 * mocks — persistence is covered by {@code UserRepositoryTest} and
 * non-blocking delivery by {@code AsyncRetryingMailSenderTest} (AU-09).
 */
class UserServiceImplTest {

    private static final String BASE_URL = "http://localhost:5173";
    private static final String EMAIL = "reader@example.com";
    private static final String RAW_TOKEN = "cafebabe".repeat(8);
    private static final String CONFIRMATION_LINK = BASE_URL + "/verify-email?token=" + RAW_TOKEN;
    private static final UUID USER_ID = UUID.fromString("0b8f4b2e-9d3a-4c1e-8a2b-1f2e3d4c5b6a");
    private static final String STORED_TOKEN_HASH = "0123456789abcdef".repeat(4);

    private final UserRepository userRepository = mock(UserRepository.class);
    private final ConfirmationTokenService confirmationTokenService = mock(ConfirmationTokenService.class);
    private final MailSender mailSender = mock(MailSender.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);
    // The generic execute() rules out a lambda; running the callback inline is all
    // these unit tests need — transaction semantics are Spring's own tested behavior.
    private final TransactionOperations inlineTransaction = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    };

    private final UserService service = new UserServiceImpl(
            userRepository, confirmationTokenService, mailSender, passwordEncoder,
            inlineTransaction, new FrontendProperties(BASE_URL));

    // ---------------------------------------------------------------- AU-11: happy path

    @Test
    void register_whenNewEmail_persistsUnverifiedCustomerWithBcrypt12HashOfVerbatimPassword() {
        stubUnknownEmail();

        service.register(new RegisterRequest(EMAIL, " Bookworm7 "));

        User saved = captureSaved();
        assertThat(saved.getEmail()).isEqualTo(EMAIL);
        assertThat(saved.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(saved.getStatus()).isEqualTo(UserStatus.UNVERIFIED);
        // BCrypt-12 (AGENTS.md §5) of the password kept verbatim — spaces and all (LC-26).
        assertThat(saved.getPasswordHash()).startsWith("$2a$12$").isNotEqualTo(" Bookworm7 ");
        assertThat(passwordEncoder.matches(" Bookworm7 ", saved.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("Bookworm7", saved.getPasswordHash())).isFalse();
    }

    @Test
    void register_mixedCaseAndWhitespaceEmail_normalizes() {
        // Plan §6.1 oracle (LC-15, LC-24, LC-26): casing and padding collapse
        // to one canonical address before storage, mail and response alike.
        stubUnknownEmail();
        when(confirmationTokenService.issue(any())).thenReturn(RAW_TOKEN);

        RegisterResponse response = service.register(new RegisterRequest("  Reader@Example.COM  ", "Bookworm7"));

        assertThat(captureSaved().getEmail()).isEqualTo(EMAIL);
        assertThat(response.email()).isEqualTo(EMAIL);
        verify(mailSender).sendConfirmation(eq(EMAIL), any());
    }

    @Test
    void register_whenEmailIsPlusAddressed_keepsTagAsDistinctAccount() {
        // LC-25: plus-addressing must survive normalization untouched.
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        when(confirmationTokenService.issue(any())).thenReturn(RAW_TOKEN);

        RegisterResponse response = service.register(new RegisterRequest(" Reader+news@Example.com ", "Bookworm7"));

        assertThat(captureSaved().getEmail()).isEqualTo("reader+news@example.com");
        assertThat(response.email()).isEqualTo("reader+news@example.com");
    }

    @Test
    void register_whenNewEmail_dispatchesConfirmationLinkBuiltPerD01() {
        stubUnknownEmail();
        when(confirmationTokenService.issue(any())).thenReturn(RAW_TOKEN);

        service.register(new RegisterRequest(EMAIL, "Bookworm7"));

        verify(mailSender).sendConfirmation(eq(EMAIL), eq(CONFIRMATION_LINK));
    }

    @Test
    void register_whenBaseUrlEndsInSlash_linkCarriesSingleSeparator() {
        UserService slashyService = new UserServiceImpl(userRepository, confirmationTokenService, mailSender,
                passwordEncoder, inlineTransaction, new FrontendProperties("http://localhost:5173/"));
        stubUnknownEmail();
        when(confirmationTokenService.issue(any())).thenReturn(RAW_TOKEN);

        slashyService.register(new RegisterRequest(EMAIL, "Bookworm7"));

        verify(mailSender).sendConfirmation(eq(EMAIL), eq(CONFIRMATION_LINK));
    }

    @Test
    void register_whenNewEmail_dispatchesConfirmationOnlyAfterTheWriteTransactionClosed() {
        // Regression (FR-01, LC-18): a link emailed while the account + token
        // write can still roll back would dangle — the dispatch must happen only
        // after TransactionOperations.execute has returned, i.e. after commit.
        AtomicBoolean transactionOpen = new AtomicBoolean();
        TransactionOperations trackingTransaction = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                transactionOpen.set(true);
                try {
                    return action.doInTransaction(null);
                } finally {
                    transactionOpen.set(false);
                }
            }
        };
        UserService trackedService = new UserServiceImpl(userRepository, confirmationTokenService, mailSender,
                passwordEncoder, trackingTransaction, new FrontendProperties(BASE_URL));
        stubUnknownEmail();
        when(confirmationTokenService.issue(any())).thenReturn(RAW_TOKEN);
        AtomicBoolean openWhenSent = new AtomicBoolean(true);
        doAnswer(invocation -> {
            openWhenSent.set(transactionOpen.get());
            return null;
        }).when(mailSender).sendConfirmation(any(), any());

        trackedService.register(new RegisterRequest(EMAIL, "Bookworm7"));

        assertThat(openWhenSent).isFalse();
        verify(mailSender).sendConfirmation(eq(EMAIL), eq(CONFIRMATION_LINK));
    }

    // ---------------------------------------------------------------- AU-12: duplicate branches

    @Test
    void register_existingVerifiedUser_sendsAlreadyRegisteredEmail() {
        // FR-01/LC-01 (plan §6.1): a VERIFIED duplicate creates nothing and
        // touches no tokens — only the owner-addressed notice leaves.
        User verified = storedUser(UserStatus.VERIFIED);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(verified));

        RegisterResponse response = service.register(new RegisterRequest("READER@example.com", "Bookworm7"));

        verify(mailSender).sendAlreadyRegisteredNotification(EMAIL);
        verify(mailSender, never()).sendConfirmation(any(), any());
        verifyNoInteractions(confirmationTokenService);
        verify(userRepository, never()).saveAndFlush(any());
        assertThat(response).isEqualTo(RegisterResponse.unverified(EMAIL));
    }

    @Test
    void register_unverifiedDuplicate_rotatesConfirmationToken() {
        // FR-01 (plan §6.1): the UNVERIFIED owner gets a fresh link, which
        // invalidates every previously issued one.
        User unverified = storedUser(UserStatus.UNVERIFIED);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(unverified));
        when(confirmationTokenService.rotate(unverified)).thenReturn(RAW_TOKEN);

        RegisterResponse response = service.register(new RegisterRequest(EMAIL, "Bookworm7"));

        verify(confirmationTokenService).rotate(unverified);
        verify(confirmationTokenService, never()).issue(any());
        verify(mailSender).sendConfirmation(EMAIL, CONFIRMATION_LINK);
        verify(userRepository, never()).saveAndFlush(any());
        assertThat(response).isEqualTo(RegisterResponse.unverified(EMAIL));
    }

    @Test
    void register_unverifiedDuplicateWithinThrottle_sendsNothingAndKeepsEnvelope() {
        // D-04: inside the 60-second window the active link simply survives —
        // rotating would kill it while no replacement email could go out.
        User unverified = storedUser(UserStatus.UNVERIFIED);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(unverified));
        when(confirmationTokenService.isResendThrottled(unverified)).thenReturn(true);

        RegisterResponse response = service.register(new RegisterRequest(EMAIL, "Bookworm7"));

        verifyNoInteractions(mailSender);
        verify(confirmationTokenService, never()).rotate(any());
        assertThat(response).isEqualTo(RegisterResponse.unverified(EMAIL));
    }

    @Test
    void register_whenRaceLoserHitsUniqueConstraint_fallsThroughToDuplicateBranch() {
        // LC-19: the concurrent winner committed between our lookup and INSERT;
        // saveAndFlush raises the constraint error and exactly one account —
        // the winner's — drives the rest of the request.
        User winner = storedUser(UserStatus.UNVERIFIED);
        when(userRepository.findByEmail(EMAIL))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(userRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uk_users_email"));
        when(confirmationTokenService.rotate(winner)).thenReturn(RAW_TOKEN);

        RegisterResponse response = service.register(new RegisterRequest(EMAIL, "Bookworm7"));

        assertThat(response).isEqualTo(RegisterResponse.unverified(EMAIL));
        verify(confirmationTokenService, never()).issue(any());
        verify(mailSender).sendConfirmation(EMAIL, CONFIRMATION_LINK);
    }

    @Test
    void register_allDuplicateBranches_returnByteIdenticalResponses() throws Exception {
        // LC-01/LC-19: new, VERIFIED-duplicate, rotated-unverified-duplicate —
        // the HTTP answer cannot tell them apart, serialized byte for byte.
        ObjectMapper objectMapper = new ObjectMapper();

        stubUnknownEmail();
        when(confirmationTokenService.issue(any())).thenReturn(RAW_TOKEN);
        RegisterResponse fresh = service.register(new RegisterRequest(EMAIL, "Bookworm7"));

        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(storedUser(UserStatus.VERIFIED)));
        RegisterResponse verifiedDuplicate = service.register(new RegisterRequest(EMAIL, "Bookworm7"));

        User unverified = storedUser(UserStatus.UNVERIFIED);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(unverified));
        when(confirmationTokenService.rotate(unverified)).thenReturn(RAW_TOKEN);
        RegisterResponse unverifiedDuplicate = service.register(new RegisterRequest(EMAIL, "Bookworm7"));

        assertThat(verifiedDuplicate).isEqualTo(fresh);
        assertThat(unverifiedDuplicate).isEqualTo(fresh);
        assertThat(objectMapper.writeValueAsString(unverifiedDuplicate))
                .isEqualTo(objectMapper.writeValueAsString(verifiedDuplicate))
                .isEqualTo(objectMapper.writeValueAsString(fresh));
    }

    // ---------------------------------------------------------------- AU-13: confirm (FR-02)

    @Test
    void confirm_whenAccountUnverifiedAndLinkLive_verifiesAtomicallyAndReportsConfirmed() {
        User unverified = storedAccount(UserStatus.UNVERIFIED);
        stubStoredToken(unverified, Instant.now().plus(23, ChronoUnit.HOURS));
        when(userRepository.verifyIfUnverified(eq(USER_ID), any(Instant.class))).thenReturn(1);
        Instant before = Instant.now();

        ConfirmResponse response = service.confirm(new ConfirmRequest(RAW_TOKEN));

        assertThat(response).isEqualTo(ConfirmResponse.confirmed());
        ArgumentCaptor<Instant> verifiedAt = ArgumentCaptor.forClass(Instant.class);
        verify(userRepository).verifyIfUnverified(eq(USER_ID), verifiedAt.capture());
        // The audit stamp rides the same atomic statement (ADR-002: @Modifying
        // paths never fire @UpdateTimestamp) and is "now", not the token's age.
        assertThat(verifiedAt.getValue()).isBetween(before.minusSeconds(5), Instant.now().plusSeconds(5));
        verifyNoInteractions(mailSender);
    }

    @Test
    void confirm_expired_returnsExpiredWithResendHint() {
        // LC-02 (plan §6.1): the row is stored but past its 24-hour window —
        // 410, the resend hint, and no status write.
        User unverified = storedAccount(UserStatus.UNVERIFIED);
        stubStoredToken(unverified, Instant.now().minus(1, ChronoUnit.MINUTES));

        assertThatThrownBy(() -> service.confirm(new ConfirmRequest(RAW_TOKEN)))
                .isInstanceOfSatisfying(ConfirmationTokenException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.GONE);
                    assertThat(ex.getType()).isEqualTo("urn:foley-books:problem:expired-confirmation-token");
                    assertThat(ex.getProperties()).containsEntry("resendHint",
                            "Request a new link with POST /api/v1/auth/resend.");
                    assertThat(ex.getMessage()).doesNotContain(RAW_TOKEN);
                });

        verify(userRepository, never()).verifyIfUnverified(any(), any());
        verifyNoInteractions(mailSender);
    }

    @Test
    void confirm_verifiedUser_idempotent() {
        // LC-03/LC-23 (plan §6.1): the row survives verification (ADR-002), so
        // re-opening a spent link — here even one long expired — answers the
        // idempotent success without a second write. The status check runs
        // before the expiry check precisely for this case.
        User verified = storedAccount(UserStatus.VERIFIED);
        stubStoredToken(verified, Instant.now().minus(48, ChronoUnit.HOURS));

        ConfirmResponse response = service.confirm(new ConfirmRequest(RAW_TOKEN));

        assertThat(response).isEqualTo(ConfirmResponse.alreadyConfirmed());
        verify(userRepository, never()).verifyIfUnverified(any(), any());
        verifyNoInteractions(mailSender);
    }

    @Test
    void confirm_whenTokenResolvesToNoRow_throwsInvalidOrExpiredWithResendHint() {
        // A never-issued or superseded (rotated-away) link is indistinguishable
        // from an expired one: 410 invalid-or-expired, still with the hint.
        when(confirmationTokenService.find(RAW_TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(new ConfirmRequest(RAW_TOKEN)))
                .isInstanceOfSatisfying(ConfirmationTokenException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.GONE);
                    assertThat(ex.getType()).isEqualTo("urn:foley-books:problem:invalid-or-expired-confirmation-token");
                    assertThat(ex.getProperties()).containsKey("resendHint");
                });

        verifyNoInteractions(userRepository);
    }

    @Test
    void confirm_whenAtomicUpdateMatchesNoRow_reportsAlreadyConfirmed() {
        // LC-23: between the read and the UPDATE a concurrent request won the
        // row; verifyIfUnverified affects 0 rows and this call degrades to the
        // same idempotent success instead of double-verifying or erroring.
        User unverified = storedAccount(UserStatus.UNVERIFIED);
        stubStoredToken(unverified, Instant.now().plus(23, ChronoUnit.HOURS));
        when(userRepository.verifyIfUnverified(eq(USER_ID), any(Instant.class))).thenReturn(0);

        ConfirmResponse response = service.confirm(new ConfirmRequest(RAW_TOKEN));

        assertThat(response).isEqualTo(ConfirmResponse.alreadyConfirmed());
    }

    // ---------------------------------------------------------------- helpers

    private void stubStoredToken(User owner, Instant expiresAt) {
        when(confirmationTokenService.find(RAW_TOKEN))
                .thenReturn(Optional.of(new ConfirmationToken(owner, STORED_TOKEN_HASH, expiresAt)));
    }

    private static User storedAccount(UserStatus status) {
        User user = storedUser(status);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return user;
    }

    private void stubUnknownEmail() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
    }

    private static User storedUser(UserStatus status) {
        return new User(EMAIL, "$2a$12$" + "x".repeat(53), UserRole.CUSTOMER, status);
    }

    private User captureSaved() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }
}
