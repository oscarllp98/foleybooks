package com.foleybooks.auth.user.service;

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
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * FR-01 registration with every duplicate branch (AU-11/AU-12) and FR-02 email
 * confirmation (AU-13). The email is normalized before anything else touches it
 * — trim + lowercase under {@link Locale#ROOT} (LC-15, LC-24, LC-26; the
 * boundary deserializer already hands over the canonical value and
 * {@code ck_users_email_lower} is the at-rest backstop), while the password is
 * encoded verbatim with the BCrypt-12 {@link PasswordEncoder} and new accounts
 * start {@code UNVERIFIED} with the {@code CUSTOMER} role.
 *
 * <p>The three branches (plan §4) all end in the identical 201 envelope
 * ({@link RegisterResponse#unverified}), so a caller can never tell which one
 * ran (LC-01): a new address creates the account and dispatches the
 * confirmation link; a VERIFIED duplicate only triggers the owner's
 * "already registered" notice; an UNVERIFIED duplicate re-issues a fresh link
 * — unless the D-04 resend throttle still has it covered, in which case the
 * previous link simply stays valid and nothing is sent. A lost
 * {@code uk_users_email} race (LC-19) falls through to the duplicate path, so
 * exactly one account ever exists per address.
 *
 * <p>Transactions are deliberately per write, not per request: the new-account
 * INSERT + first token issue run atomically in one {@link TransactionOperations}
 * unit, and {@code saveAndFlush} surfaces the race as a caught
 * {@link DataIntegrityViolationException} <em>inside</em> that unit — a
 * request-scoped transaction could not recover after the constraint error
 * aborted it. Confirmation mail only leaves once that unit has committed
 * (FR-01/LC-18: a rolled-back account must never orphan a live link), and
 * dispatch is async on top, so no delivery outcome can influence the response
 * (C24). Token re-issuance is transactional in its own service. FR-02
 * confirmation has no such race to recover from, so it takes the ordinary
 * request-scoped {@code @Transactional} boundary instead: one read/write
 * transaction covering the token lookup, the status/expiry checks and the
 * atomic verification UPDATE.
 */
@Service
public class UserServiceImpl implements UserService {

    private static final String VERIFY_EMAIL_PATH = "/verify-email?token=";

    private final UserRepository userRepository;
    private final ConfirmationTokenService confirmationTokenService;
    private final MailSender mailSender;
    private final PasswordEncoder passwordEncoder;
    private final TransactionOperations transactionOperations;
    private final String frontendBaseUrl;

    public UserServiceImpl(UserRepository userRepository,
                           ConfirmationTokenService confirmationTokenService,
                           MailSender mailSender,
                           PasswordEncoder passwordEncoder,
                           TransactionOperations transactionOperations,
                           FrontendProperties frontendProperties) {
        this.userRepository = userRepository;
        this.confirmationTokenService = confirmationTokenService;
        this.mailSender = mailSender;
        this.passwordEncoder = passwordEncoder;
        this.transactionOperations = transactionOperations;
        this.frontendBaseUrl = frontendProperties.baseUrl().endsWith("/")
                ? frontendProperties.baseUrl().substring(0, frontendProperties.baseUrl().length() - 1)
                : frontendProperties.baseUrl();
    }

    @Override
    public RegisterResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());

        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isEmpty()) {
            try {
                String rawToken = createUnverifiedUser(email, request.password());
                // Post-commit dispatch (FR-01, LC-18): the transaction unit is closed
                // and durable by now, so a rolled-back account can never orphan a
                // live confirmation link.
                mailSender.sendConfirmation(email, confirmationLink(rawToken));
                return RegisterResponse.unverified(email);
            } catch (DataIntegrityViolationException raceLost) {
                // LC-19: a concurrent registration won uk_users_email first —
                // its INSERT committed, ours never happened, so this request
                // just follows the duplicate path against the winner's account.
                existing = userRepository.findByEmail(email);
            }
        }
        dispatchDuplicateBranch(existing.orElseThrow(() -> new IllegalStateException(
                "Email vanished between the pre-check and the post-race re-read.")));
        return RegisterResponse.unverified(email);
    }

    /**
     * FR-02 confirmation (AU-13). Runs in one plain read/write transaction —
     * unlike registration there is no constraint race to catch, and the lazy
     * {@code token.getUser()} read plus the guarded bulk UPDATE must share one
     * persistence context. The checks follow the plan §4 order deliberately:
     * the VERIFIED status is tested <em>before</em> the expiry window so
     * re-opening a long-dead link on a confirmed account answers the idempotent
     * success of LC-03, not a confusing 410. Verification itself is the single
     * atomic {@code UPDATE … WHERE status = 'UNVERIFIED'} (LC-23): concurrent
     * double-use lets exactly one caller flip the row, and the loser — whose
     * statement matches zero rows once the winner commits — still answers
     * success. The confirmation row is never deleted (ADR-002): retaining it is
     * what keeps a spent link resolvable.
     */
    @Override
    @Transactional
    public ConfirmResponse confirm(ConfirmRequest request) {
        Instant now = Instant.now();
        ConfirmationToken token = confirmationTokenService.find(request.token())
                .orElseThrow(ConfirmationTokenException::invalidOrExpired);

        User user = token.getUser();
        if (user.getStatus() == UserStatus.VERIFIED) {
            return ConfirmResponse.alreadyConfirmed();
        }
        if (token.getExpiresAt().isBefore(now)) {
            throw ConfirmationTokenException.expired();
        }

        return userRepository.verifyIfUnverified(user.getId(), now) == 1
                ? ConfirmResponse.confirmed()
                : ConfirmResponse.alreadyConfirmed();
    }

    /** Atomically persists the account + first confirmation link; returns the raw token for post-commit dispatch. */
    private String createUnverifiedUser(String email, String rawPassword) {
        return transactionOperations.execute(status -> {
            User user = new User(email, passwordEncoder.encode(rawPassword),
                    UserRole.CUSTOMER, UserStatus.UNVERIFIED);
            // saveAndFlush: the LC-19 race must surface as a caught exception at
            // the INSERT, not as a commit failure after the callback returned.
            userRepository.saveAndFlush(user);
            return confirmationTokenService.issue(user);
        });
    }

    /** FR-01 duplicate handling; caller-visible behavior is identical for every branch. */
    private void dispatchDuplicateBranch(User existing) {
        String email = existing.getEmail();
        if (existing.getStatus() == UserStatus.VERIFIED) {
            // LC-01: only the real owner learns the address is taken — by the
            // notice landing in their inbox, never by this response.
            mailSender.sendAlreadyRegisteredNotification(email);
            return;
        }
        if (confirmationTokenService.isResendThrottled(existing)) {
            // D-04: a link issued under a minute ago is still the active one;
            // rotating now would silently kill it while sending nothing new.
            return;
        }
        // UNVERIFIED duplicate (including the LC-19 race loser): the owner gets
        // a fresh link, invalidating every previously issued one.
        String rawToken = confirmationTokenService.rotate(existing);
        mailSender.sendConfirmation(email, confirmationLink(rawToken));
    }

    /** Trim + lowercase (LC-15/LC-26); plus-addressing is preserved (LC-25). */
    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String confirmationLink(String rawToken) {
        return frontendBaseUrl + VERIFY_EMAIL_PATH + rawToken;
    }
}
