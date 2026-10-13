package com.foleybooks.auth.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * SMTP adapter (ADR-001) — the only non-config code referencing Spring mail
 * types (C7). One implementation serves both the Mailpit dev target and any
 * real provider: host/port come from {@code spring.mail.*} (dev defaults in
 * {@code application.yml}, compose sets {@code mailpit:1025}, production
 * overrides via env — C19). Messages are plain text (no template engine
 * dependency, C2). The sender address comes from the app-owned
 * {@code app.mail.from} knob and is stamped on every message: Boot's
 * {@code MailProperties}/{@code JavaMailSenderImpl} have no from support at
 * all. Recipients are logged but confirmation links —
 * and thus tokens — never are (C24).
 */
public class SmtpMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailSender.class);

    static final String CONFIRMATION_SUBJECT = "Confirm your Foley Books account";
    static final String ALREADY_REGISTERED_SUBJECT = "Your Foley Books account is already registered";

    private final JavaMailSender javaMailSender;
    private final String from;

    public SmtpMailSender(JavaMailSender javaMailSender, String from) {
        this.javaMailSender = javaMailSender;
        this.from = from;
    }

    @Override
    public void sendConfirmation(String recipient, String confirmationLink) {
        SimpleMailMessage message = newNotice(recipient, CONFIRMATION_SUBJECT);
        message.setText("""
                Welcome to Foley Books!

                Please confirm your email address to activate your account:

                %s

                This link is valid for 24 hours. If you did not create this
                account, you can safely ignore this email.
                """.formatted(confirmationLink));
        javaMailSender.send(message);
        log.info("Confirmation email sent to {}", recipient);
    }

    @Override
    public void sendAlreadyRegisteredNotification(String recipient) {
        SimpleMailMessage message = newNotice(recipient, ALREADY_REGISTERED_SUBJECT);
        message.setText("""
                Someone tried to register a Foley Books account with this email
                address, but it is already registered. If this was you, simply
                sign in — no further action is needed.
                """);
        javaMailSender.send(message);
        log.info("Already-registered notice sent to {}", recipient);
    }

    private SimpleMailMessage newNotice(String recipient, String subject) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject(subject);
        return message;
    }
}
