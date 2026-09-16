package com.foleybooks.auth.config;

import com.foleybooks.auth.mail.LoggingMailSender;
import com.foleybooks.auth.mail.MailSender;
import com.foleybooks.auth.mail.SmtpMailSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Mail transport selection (ADR-001): property-driven, not profile-driven —
 * {@code app.mail.transport=smtp} (default) wires the SMTP adapter against
 * {@code spring.mail.*} (Mailpit in dev/docker), while {@code log} wires the
 * recording adapter for context-booting integration tests. The sender address
 * is the app-owned {@code app.mail.from} knob: Boot's {@code MailProperties}
 * has no from field, so the adapter stamps it on every message.
 */
@Configuration(proxyBeanMethods = false)
public class MailConfig {

    @Bean
    @ConditionalOnProperty(prefix = "app.mail", name = "transport", havingValue = "log")
    public MailSender loggingMailSender() {
        return new LoggingMailSender();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.mail", name = "transport", havingValue = "smtp", matchIfMissing = true)
    public MailSender smtpMailSender(JavaMailSender javaMailSender, @Value("${app.mail.from}") String from) {
        return new SmtpMailSender(javaMailSender, from);
    }
}
