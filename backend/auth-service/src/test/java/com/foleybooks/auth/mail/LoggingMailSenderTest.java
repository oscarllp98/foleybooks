package com.foleybooks.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.foleybooks.auth.mail.LoggingMailSender.Kind;
import com.foleybooks.auth.mail.LoggingMailSender.LoggedMail;
import org.junit.jupiter.api.Test;

/**
 * The recording adapter used by context-booting ITs (ADR-001, D-02): intent is
 * observable, and the confirmation link — the token bearer — is never retained
 * or logged (C24).
 */
class LoggingMailSenderTest {

    private final LoggingMailSender sender = new LoggingMailSender();

    @Test
    void sent_whenNothingDispatched_returnsEmpty() {
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void sendConfirmation_whenInvoked_recordsIntentWithoutRetainingLink() {
        sender.sendConfirmation("reader@example.com", "http://localhost:5173/verify-email?token=abc123");

        assertThat(sender.sent()).containsExactly(new LoggedMail("reader@example.com", Kind.CONFIRMATION));
        assertThat(sender.sent().getFirst().toString()).doesNotContain("abc123").doesNotContain("verify-email");
    }

    @Test
    void sendAlreadyRegisteredNotification_whenInvoked_recordsNotice() {
        sender.sendAlreadyRegisteredNotification("reader@example.com");

        assertThat(sender.sent())
                .containsExactly(new LoggedMail("reader@example.com", Kind.ALREADY_REGISTERED));
    }
}
