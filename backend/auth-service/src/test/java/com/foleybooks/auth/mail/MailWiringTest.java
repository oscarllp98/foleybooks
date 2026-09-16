package com.foleybooks.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proof that the ADR-001 transport knob wires the recording adapter into a
 * full context — the setup AU-19/AU-20 integration tests will reuse.
 */
@Testcontainers
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "EUREKA_USERNAME=test-user",
        "EUREKA_PASSWORD=test-secret",
        "AUTH_DB_PASSWORD=test-db-secret",
        "app.mail.transport=log"
})
class MailWiringTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MailSender mailSender;

    @Test
    void mailSender_whenTransportLog_wiresRecordingAdapter() {
        assertThat(mailSender).isInstanceOf(LoggingMailSender.class);
    }
}
