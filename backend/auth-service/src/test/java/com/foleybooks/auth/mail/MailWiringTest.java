package com.foleybooks.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.foleybooks.auth.mail.LoggingMailSender.Kind;
import com.foleybooks.auth.mail.LoggingMailSender.LoggedMail;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.scheduling.TaskScheduler;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proof that the ADR-001 transport knob plus the AU-09 async layer wire into
 * a full context — the setup AU-19/AU-20 integration tests will reuse: the
 * {@link MailSender} port resolves to the async-retry decorator, the raw
 * recording adapter stays addressable by type, and a send dispatched through
 * the decorator lands in the recording adapter off-thread without ever
 * touching SMTP (LC-18, D-02).
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

    @Autowired
    LoggingMailSender recordingDelegate;

    @Autowired
    @Qualifier("mailExecutor")
    Executor mailExecutor;

    @Autowired
    @Qualifier("mailTaskScheduler")
    TaskScheduler mailTaskScheduler;

    @Test
    void mailSender_whenTransportLog_wiresAsyncDecoratorOverRecordingAdapter() {
        assertThat(mailSender).isInstanceOf(AsyncRetryingMailSender.class);
        assertThat(mailExecutor).isNotNull();
        assertThat(mailTaskScheduler).isNotNull();
    }

    @Test
    void sendConfirmation_whenTransportLog_dispatchesAsyncIntoRecordingAdapter() throws InterruptedException {
        mailSender.sendConfirmation("reader@example.com", "http://localhost:5173/verify-email?token=abc123");

        assertThat(awaitDispatched()).containsExactly(new LoggedMail("reader@example.com", Kind.CONFIRMATION));
    }

    /** The pool records off-thread; bounded polling keeps the assertion instant and flake-free. */
    private List<LoggedMail> awaitDispatched() throws InterruptedException {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (recordingDelegate.sent().isEmpty() && System.nanoTime() < deadlineNanos) {
            Thread.sleep(20);
        }
        return recordingDelegate.sent();
    }
}
