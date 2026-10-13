package com.foleybooks.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.foleybooks.auth.mail.AsyncRetryingMailSender;
import com.foleybooks.auth.mail.MailSender;
import com.foleybooks.auth.mail.SmtpMailSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "EUREKA_USERNAME=test-user",
        "EUREKA_PASSWORD=test-secret",
        "AUTH_DB_PASSWORD=test-db-secret"
})
class AuthServiceApplicationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MailSender mailSender;

    @Autowired
    SmtpMailSender smtpDelegate;

    @Test
    void startup_whenPostgresAvailable_bootsSuccessfully() {
    }

    @Test
    void mailSender_whenTransportDefaultsToSmtp_wiresAsyncDecoratorOverSmtpAdapter() {
        // ADR-001 + AU-09: real deploys (dev/docker) inject the port as the async
        // retry decorator; the SMTP adapter stays behind it as a raw bean.
        assertThat(mailSender).isInstanceOf(AsyncRetryingMailSender.class);
        assertThat(smtpDelegate).isNotNull();
    }
}
