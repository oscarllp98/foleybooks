package com.foleybooks.auth.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.foleybooks.auth.mail.MailSender;
import com.foleybooks.auth.token.service.ConfirmationTokenService;
import com.foleybooks.auth.user.api.RegisterRequest;
import com.foleybooks.auth.user.api.RegisterResponse;
import com.foleybooks.auth.user.domain.User;
import com.foleybooks.auth.user.domain.UserRole;
import com.foleybooks.auth.user.domain.UserStatus;
import com.foleybooks.auth.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Plain JUnit + Mockito unit tests for FR-01 registration (plan §6.1). The
 * password codec is the real {@code BCryptPasswordEncoder(12)} so the
 * "BCrypt strength 12" claim is proven, not mocked; repositories and the mail
 * port are mocks — persistence is covered by {@code UserRepositoryTest} and
 * non-blocking delivery by {@code AsyncRetryingMailSenderTest} (AU-09).
 */
class UserServiceImplTest {

    private static final String BASE_URL = "http://localhost:5173";
    private static final String RAW_TOKEN = "cafebabe".repeat(8);

    private final UserRepository userRepository = mock(UserRepository.class);
    private final ConfirmationTokenService confirmationTokenService = mock(ConfirmationTokenService.class);
    private final MailSender mailSender = mock(MailSender.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

    private final UserService service =
            new UserServiceImpl(userRepository, confirmationTokenService, mailSender, passwordEncoder, BASE_URL);

    @Test
    void register_whenNewEmail_persistsUnverifiedCustomerWithBcrypt12HashOfVerbatimPassword() {
        when(userRepository.findByEmail("reader@example.com")).thenReturn(Optional.empty());

        service.register(new RegisterRequest("reader@example.com", " Bookworm7 "));

        User saved = captureSaved();
        assertThat(saved.getEmail()).isEqualTo("reader@example.com");
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
        when(userRepository.findByEmail("reader@example.com")).thenReturn(Optional.empty());
        when(confirmationTokenService.issue(any())).thenReturn(RAW_TOKEN);

        RegisterResponse response = service.register(new RegisterRequest("  Reader@Example.COM  ", "Bookworm7"));

        assertThat(captureSaved().getEmail()).isEqualTo("reader@example.com");
        assertThat(response.email()).isEqualTo("reader@example.com");
        verify(mailSender).sendConfirmation(eq("reader@example.com"), any());
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
        when(userRepository.findByEmail("reader@example.com")).thenReturn(Optional.empty());
        when(confirmationTokenService.issue(any())).thenReturn(RAW_TOKEN);

        service.register(new RegisterRequest("reader@example.com", "Bookworm7"));

        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(mailSender).sendConfirmation(eq("reader@example.com"), link.capture());
        assertThat(link.getValue()).isEqualTo(BASE_URL + "/verify-email?token=" + RAW_TOKEN);
    }

    @Test
    void register_whenBaseUrlEndsInSlash_linkCarriesSingleSeparator() {
        UserService slashyService = new UserServiceImpl(userRepository, confirmationTokenService, mailSender,
                passwordEncoder, "http://localhost:5173/");
        when(userRepository.findByEmail("reader@example.com")).thenReturn(Optional.empty());
        when(confirmationTokenService.issue(any())).thenReturn(RAW_TOKEN);

        slashyService.register(new RegisterRequest("reader@example.com", "Bookworm7"));

        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(mailSender).sendConfirmation(any(), link.capture());
        assertThat(link.getValue()).isEqualTo(BASE_URL + "/verify-email?token=" + RAW_TOKEN);
    }

    @Test
    void register_whenEmailAlreadyKnown_returnsIdenticalEnvelopeWithoutNewAccount() {
        // Interim AU-11 behavior: the duplicate stays enumeration-safe and
        // creates nothing; AU-12 adds the branch mails and race handling.
        when(userRepository.findByEmail("reader@example.com"))
                .thenReturn(Optional.of(new User("reader@example.com", "$2a$12$" + "x".repeat(53),
                        UserRole.CUSTOMER, UserStatus.VERIFIED)));

        RegisterResponse response = service.register(new RegisterRequest("READER@example.com", "Bookworm7"));

        assertThat(response).isEqualTo(RegisterResponse.unverified("reader@example.com"));
        assertThat(response.status()).isEqualTo(UserStatus.UNVERIFIED);
        assertThat(response.message()).isEqualTo("Confirmation is on its way. Check your inbox (and spam folder).");
        verify(userRepository, never()).save(any());
        verifyNoInteractions(confirmationTokenService);
    }

    private User captureSaved() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        return captor.getValue();
    }
}
