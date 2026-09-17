package com.foleybooks.auth.user.service;

import com.foleybooks.auth.mail.MailSender;
import com.foleybooks.auth.token.service.ConfirmationTokenService;
import com.foleybooks.auth.user.api.RegisterRequest;
import com.foleybooks.auth.user.api.RegisterResponse;
import com.foleybooks.auth.user.domain.User;
import com.foleybooks.auth.user.domain.UserRole;
import com.foleybooks.auth.user.domain.UserStatus;
import com.foleybooks.auth.user.repository.UserRepository;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-01 registration (happy path). The email is normalized before anything
 * else touches it — trim + lowercase under {@link Locale#ROOT} (LC-15, LC-24,
 * LC-26; {@code ck_users_email_lower} is the at-rest backstop), while the
 * password is encoded verbatim with the BCrypt-12 {@link PasswordEncoder} and
 * the account starts {@code UNVERIFIED} with the {@code CUSTOMER} role.
 *
 * <p>The confirmation link is built per D-01 from {@code app.frontend.base-url}
 * and handed to the {@link MailSender} port; the injected decorator dispatches
 * it off-thread with retries, so delivery can never block or fail registration
 * (LC-18). The raw token exists only inside that link — never stored plainly,
 * never logged (C24).
 */
@Service
public class UserServiceImpl implements UserService {

    private static final String VERIFY_EMAIL_PATH = "/verify-email?token=";

    private final UserRepository userRepository;
    private final ConfirmationTokenService confirmationTokenService;
    private final MailSender mailSender;
    private final PasswordEncoder passwordEncoder;
    private final String frontendBaseUrl;

    public UserServiceImpl(UserRepository userRepository,
                           ConfirmationTokenService confirmationTokenService,
                           MailSender mailSender,
                           PasswordEncoder passwordEncoder,
                           @Value("${app.frontend.base-url}") String frontendBaseUrl) {
        this.userRepository = userRepository;
        this.confirmationTokenService = confirmationTokenService;
        this.mailSender = mailSender;
        this.passwordEncoder = passwordEncoder;
        this.frontendBaseUrl = frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
                : frontendBaseUrl;
    }

    @Override
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());

        // Interim duplicate handling (AU-11 scope): a known email creates no
        // second account and still receives the identical 201 envelope
        // (LC-01). AU-12 owns the full branch: verified → "already registered"
        // mail, unverified → fresh throttled link (D-04), plus the
        // unique-constraint race path (LC-19).
        if (userRepository.findByEmail(email).isPresent()) {
            return RegisterResponse.unverified(email);
        }

        User user = new User(email, passwordEncoder.encode(request.password()),
                UserRole.CUSTOMER, UserStatus.UNVERIFIED);
        userRepository.save(user);

        String rawToken = confirmationTokenService.issue(user);
        mailSender.sendConfirmation(email, confirmationLink(rawToken));
        return RegisterResponse.unverified(email);
    }

    /** Trim + lowercase (LC-15/LC-26); plus-addressing is preserved (LC-25). */
    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String confirmationLink(String rawToken) {
        return frontendBaseUrl + VERIFY_EMAIL_PATH + rawToken;
    }
}
