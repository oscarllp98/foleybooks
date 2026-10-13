package com.foleybooks.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.captor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Plain JUnit + Mockito pyramid layer 1 (AGENTS.md §8): the SMTP adapter's
 * message shape (FR-01, FR-02), Spring's mail API mocked at the boundary.
 */
class SmtpMailSenderTest {

    private static final String FROM = "noreply@foleybooks.com";

    private final JavaMailSender javaMailSender = mock(JavaMailSender.class);
    private final SmtpMailSender sender = new SmtpMailSender(javaMailSender, FROM);

    @Test
    void sendConfirmation_whenInvoked_sendsPlainEmailCarryingConfirmationLink() {
        sender.sendConfirmation("reader@example.com", "http://localhost:5173/verify-email?token=abc123");

        SimpleMailMessage message = captureSent();
        // app.mail.from is stamped per message: Boot's mail stack has no from
        // support, and providers reject sender-less mail.
        assertThat(message.getFrom()).isEqualTo(FROM);
        assertThat(message.getTo()).containsExactly("reader@example.com");
        assertThat(message.getSubject()).isEqualTo(SmtpMailSender.CONFIRMATION_SUBJECT);
        assertThat(message.getText()).contains("http://localhost:5173/verify-email?token=abc123");
        assertThat(message.getText()).contains("24 hours");
    }

    @Test
    void sendAlreadyRegisteredNotification_whenInvoked_sendsNoticeWithoutAnyLink() {
        sender.sendAlreadyRegisteredNotification("reader@example.com");

        SimpleMailMessage message = captureSent();
        assertThat(message.getTo()).containsExactly("reader@example.com");
        assertThat(message.getSubject()).isEqualTo(SmtpMailSender.ALREADY_REGISTERED_SUBJECT);
        assertThat(message.getText()).doesNotContain("http").doesNotContain("verify-email");
    }

    private SimpleMailMessage captureSent() {
        ArgumentCaptor<SimpleMailMessage> captor = captor();
        verify(javaMailSender).send(captor.capture());
        return captor.getValue();
    }
}
