package com.foleybooks.auth.mail;

/**
 * App-owned mail port (ADR-001): the two emails the MVP sends — the FR-01
 * confirmation link and the FR-01/LC-01 "already registered" notice. Callers
 * never wait on SMTP: implementations are synchronous primitives that AU-09
 * wraps in async dispatch with retry, so registration can never block or fail
 * on delivery (LC-18). No Spring mail type crosses this seam (C7).
 *
 * <p>Note: deliberately NOT {@code org.springframework.mail.MailSender} —
 * the Spring type is confined to {@link SmtpMailSender}.
 */
public interface MailSender {

    /**
     * Sends the FR-01/FR-02 confirmation email.
     *
     * @param recipient        normalized (lowercase) account email
     * @param confirmationLink fully built {@code <base>/verify-email?token=…} URL
     *                         per D-01; the raw token must never be logged (C24)
     */
    void sendConfirmation(String recipient, String confirmationLink);

    /**
     * Sends the "email already registered" notice for a duplicate registration
     * against a VERIFIED account (FR-01, LC-01). Enumeration-safe: delivered
     * asynchronously, response identical regardless of outcome (LC-19).
     */
    void sendAlreadyRegisteredNotification(String recipient);
}
