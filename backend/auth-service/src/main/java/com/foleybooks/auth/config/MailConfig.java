package com.foleybooks.auth.config;

import com.foleybooks.auth.mail.AsyncRetryingMailSender;
import com.foleybooks.auth.mail.LoggingMailSender;
import com.foleybooks.auth.mail.MailSender;
import com.foleybooks.auth.mail.SmtpMailSender;
import java.time.Duration;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.TaskScheduler;

/**
 * Mail transport selection (ADR-001): property-driven, not profile-driven —
 * {@code app.mail.transport=smtp} (default) wires the SMTP adapter against
 * {@code spring.mail.*} (Mailpit in dev/docker), while {@code log} wires the
 * recording adapter for context-booting integration tests. The sender address
 * is the app-owned {@code app.mail.from} knob: Boot's {@code MailProperties}
 * has no from field, so the adapter stamps it on every message itself.
 *
 * <p>AU-09: raw adapters stay exposed as beans of their concrete type (ITs
 * assert against {@link LoggingMailSender} directly), while everything
 * injecting the {@link MailSender} port gets the {@code @Primary} async
 * decorator — async dispatch plus the 3-attempt scheduled retry (D-02,
 * LC-18). The decorator's own {@code MailSender} delegate parameter resolves
 * to the single active raw adapter (the decorator is skipped as a
 * self-reference).
 */
@Configuration(proxyBeanMethods = false)
public class MailConfig {

    @Bean
    @ConditionalOnProperty(prefix = "app.mail", name = "transport", havingValue = "log")
    public LoggingMailSender loggingMailSender() {
        return new LoggingMailSender();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.mail", name = "transport", havingValue = "smtp", matchIfMissing = true)
    public SmtpMailSender smtpMailSender(JavaMailSender javaMailSender, @Value("${app.mail.from}") String from) {
        return new SmtpMailSender(javaMailSender, from);
    }

    @Bean
    @Primary
    public MailSender asyncRetryingMailSender(
            MailSender delegate,
            @Qualifier("mailExecutor") Executor mailExecutor,
            @Qualifier("mailTaskScheduler") TaskScheduler mailTaskScheduler,
            @Value("${app.mail.retry.max-attempts:3}") int maxAttempts,
            @Value("${app.mail.retry.delay:1s}") Duration retryDelay) {
        return new AsyncRetryingMailSender(delegate, mailExecutor, mailTaskScheduler, maxAttempts, retryDelay);
    }
}
