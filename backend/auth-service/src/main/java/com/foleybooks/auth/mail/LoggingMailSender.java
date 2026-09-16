package com.foleybooks.auth.mail;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test adapter (ADR-001, D-02): records mail intent instead of opening an
 * SMTP connection, so context-booting integration tests (AU-19/AU-20) never
 * depend on Mailpit. Selected with {@code app.mail.transport=log}; plain unit
 * tests mock {@link MailSender} directly (plan §6.1). Recorded entries keep
 * recipient + kind only — confirmation links (and their tokens) are neither
 * logged nor retained (C24).
 */
public class LoggingMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailSender.class);

    public enum Kind {
        CONFIRMATION,
        ALREADY_REGISTERED
    }

    /** One recorded send intent; deliberately carries no link/token payload. */
    public record LoggedMail(String recipient, Kind kind) {
    }

    private final List<LoggedMail> sent = new CopyOnWriteArrayList<>();

    @Override
    public void sendConfirmation(String recipient, String confirmationLink) {
        sent.add(new LoggedMail(recipient, Kind.CONFIRMATION));
        log.info("Mail dispatch disabled (transport=log): confirmation email recorded for {}", recipient);
    }

    @Override
    public void sendAlreadyRegisteredNotification(String recipient) {
        sent.add(new LoggedMail(recipient, Kind.ALREADY_REGISTERED));
        log.info("Mail dispatch disabled (transport=log): already-registered notice recorded for {}", recipient);
    }

    /** Immutable snapshot of the recorded intents, in send order. */
    public List<LoggedMail> sent() {
        return List.copyOf(sent);
    }
}
