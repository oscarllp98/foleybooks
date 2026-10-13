package com.foleybooks.auth.mail;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.util.Assert;

/**
 * Async dispatch decorator (AU-09, ADR-001, D-02): wraps the synchronous
 * transport adapters so callers never wait on SMTP — the send is handed to
 * {@code executor} and {@link #sendConfirmation} returns immediately, keeping
 * registration responsive even when the provider is down (FR-01, LC-18).
 *
 * <p>A failed delivery is re-attempted on {@code retryScheduler} after a fixed
 * {@code retryDelay}, up to {@code maxAttempts} attempts in total — the count
 * is per message including the initial dispatch, ADR-001's "scheduled retry of
 * 3 attempts" (1 dispatch + 2 scheduled retries at the default
 * {@code app.mail.retry.max-attempts: 3}). Retrying on the scheduler — not by
 * sleeping a dispatch thread — keeps the pool free while backoff elapses.
 * When every attempt has failed the event is logged at error level and
 * dropped: FR-02's resend is the user-visible recovery path, and a rejected
 * or exhausted dispatch must never propagate back into the request flow
 * (LC-18).
 *
 * <p>Logs carry recipient and failure cause only — confirmation links, and
 * therefore tokens, are never logged (C24).
 */
public final class AsyncRetryingMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(AsyncRetryingMailSender.class);

    private final MailSender delegate;
    private final Executor executor;
    private final TaskScheduler retryScheduler;
    private final int maxAttempts;
    private final Duration retryDelay;

    public AsyncRetryingMailSender(
            MailSender delegate,
            Executor executor,
            TaskScheduler retryScheduler,
            int maxAttempts,
            Duration retryDelay) {
        Assert.notNull(delegate, "delegate must not be null");
        Assert.notNull(executor, "executor must not be null");
        Assert.notNull(retryScheduler, "retryScheduler must not be null");
        Assert.isTrue(maxAttempts >= 1, "maxAttempts must be at least 1");
        Assert.notNull(retryDelay, "retryDelay must not be null");
        this.delegate = delegate;
        this.executor = executor;
        this.retryScheduler = retryScheduler;
        this.maxAttempts = maxAttempts;
        this.retryDelay = retryDelay;
    }

    @Override
    public void sendConfirmation(String recipient, String confirmationLink) {
        dispatch("confirmation email", recipient, () -> delegate.sendConfirmation(recipient, confirmationLink));
    }

    @Override
    public void sendAlreadyRegisteredNotification(String recipient) {
        dispatch("already-registered notice", recipient, () -> delegate.sendAlreadyRegisteredNotification(recipient));
    }

    private void dispatch(String description, String recipient, Runnable send) {
        try {
            executor.execute(() -> attempt(description, recipient, send, 1));
        } catch (RejectedExecutionException ex) {
            // Saturated pool: dropping the mail still keeps registration green (LC-18).
            log.error("Mail dispatch rejected; {} for {} not attempted — resend remains available (FR-02)",
                    description, recipient, ex);
        }
    }

    private void attempt(String description, String recipient, Runnable send, int attemptNumber) {
        try {
            send.run();
            if (attemptNumber > 1) {
                log.info("{} for {} delivered on attempt {}/{}", description, recipient, attemptNumber, maxAttempts);
            }
        } catch (RuntimeException ex) {
            handleFailure(description, recipient, send, attemptNumber, ex);
        }
    }

    private void handleFailure(
            String description, String recipient, Runnable send, int attemptNumber, RuntimeException cause) {
        if (attemptNumber >= maxAttempts) {
            log.error("Mail delivery permanently failed: {} for {} after {} attempts — resend remains available "
                    + "(FR-02)", description, recipient, maxAttempts, cause);
            return;
        }
        log.warn("Attempt {}/{} for {} to {} failed; retrying in {}",
                attemptNumber, maxAttempts, description, recipient, retryDelay, cause);
        int nextAttempt = attemptNumber + 1;
        try {
            retryScheduler.schedule(
                    () -> attempt(description, recipient, send, nextAttempt), Instant.now().plus(retryDelay));
        } catch (RuntimeException schedulingFailure) {
            log.error("Retry scheduling failed; {} for {} abandoned after attempt {}/{}",
                    description, recipient, attemptNumber, maxAttempts, schedulingFailure);
        }
    }
}
