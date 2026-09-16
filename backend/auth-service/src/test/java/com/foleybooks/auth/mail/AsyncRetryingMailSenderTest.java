package com.foleybooks.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.scheduling.TaskScheduler;
import org.slf4j.LoggerFactory;

/**
 * Plain JUnit + Mockito pyramid layer 1 (AGENTS.md §8) for AU-09 (ADR-001,
 * D-02): dispatch is off the caller's thread, failures re-attempt on the
 * scheduler up to the configured attempts, exhaustion never escalates, and
 * logs never carry the confirmation link (C24, LC-18). A recording executor
 * and a mocked {@link TaskScheduler} keep the test synchronous and instant.
 */
class AsyncRetryingMailSenderTest {

    private static final String RECIPIENT = "reader@example.com";
    private static final String LINK = "http://localhost:5173/verify-email?token=abc123";
    private static final String TOKEN = "abc123";
    private static final Duration DELAY = Duration.ofSeconds(1);
    private static final int MAX_ATTEMPTS = 3;

    private final MailSender delegate = mock(MailSender.class);
    private final TaskScheduler retryScheduler = mock(TaskScheduler.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final Executor capturingExecutor = queued::add;

    private AsyncRetryingMailSender sender;

    private Logger logbackLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        sender = newSender(capturingExecutor);
        // Retry "immediately" inline so the whole ladder is deterministic.
        when(retryScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                });
        logbackLogger = (Logger) LoggerFactory.getLogger(AsyncRetryingMailSender.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(appender);
    }

    private AsyncRetryingMailSender newSender(Executor executor) {
        return new AsyncRetryingMailSender(delegate, executor, retryScheduler, MAX_ATTEMPTS, DELAY);
    }

    private void drainQueued() {
        int processed = 0;
        while (processed < queued.size()) {
            queued.get(processed++).run();
        }
    }

    private static List<Arguments> sendInvocations() {
        return List.of(
                Arguments.of("confirmation", (Invocation) (s, r) -> s.sendConfirmation(r, LINK)),
                Arguments.of("already-registered", (Invocation) (s, r) -> s.sendAlreadyRegisteredNotification(r)));
    }

    @FunctionalInterface
    private interface Invocation {
        void call(MailSender target, String recipient);
    }

    @ParameterizedTest(name = "[{0}]")
    @MethodSource("sendInvocations")
    void send_whenInvoked_returnsBeforeAnySendAndNeverThrows(String label, Invocation invocation) {
        assertThatCode(() -> invocation.call(sender, RECIPIENT)).doesNotThrowAnyException();

        // Nothing ran yet: the caller never waits on the transport (FR-01, LC-18).
        assertThat(queued).hasSize(1);
        verifyNoInteractions(delegate);

        drainQueued();
        assertThat(appender.list).isEmpty();
    }

    @Test
    void sendConfirmation_whenDispatched_runsDelegateOnPoolTask() {
        sender.sendConfirmation(RECIPIENT, LINK);
        drainQueued();

        verify(delegate).sendConfirmation(RECIPIENT, LINK);
        verifyNoInteractions(retryScheduler);
    }

    @Test
    void sendConfirmation_whenFirstAttemptFails_retriesAfterDelayUntilSuccess() {
        doThrow(new MailSendException("provider down"))
                .doNothing()
                .when(delegate)
                .sendConfirmation(RECIPIENT, LINK);

        Instant before = Instant.now();
        sender.sendConfirmation(RECIPIENT, LINK);
        drainQueued();

        verify(delegate, times(2)).sendConfirmation(RECIPIENT, LINK);
        ArgumentCaptor<Instant> retryAt = ArgumentCaptor.forClass(Instant.class);
        verify(retryScheduler).schedule(any(Runnable.class), retryAt.capture());
        assertThat(retryAt.getValue()).isAfterOrEqualTo(before.plus(DELAY).minusMillis(500));
        assertThat(appender.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .hasSize(1)
                .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(TOKEN));
        assertThat(appender.list).noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    @Test
    void sendConfirmation_whenEveryAttemptFails_givesUpQuietlyAfterMaxAttempts() {
        doThrow(new MailSendException("provider down"))
                .when(delegate)
                .sendConfirmation(RECIPIENT, LINK);

        assertThatCode(() -> {
            sender.sendConfirmation(RECIPIENT, LINK);
            drainQueued();
        }).doesNotThrowAnyException();

        verify(delegate, times(MAX_ATTEMPTS)).sendConfirmation(RECIPIENT, LINK);
        verify(retryScheduler, times(MAX_ATTEMPTS - 1)).schedule(any(Runnable.class), any(Instant.class));
        assertLogsExhaustionWithoutToken();
    }

    @Test
    void sendAlreadyRegisteredNotification_whenEveryAttemptFails_givesUpQuietlyAfterMaxAttempts() {
        doThrow(new MailSendException("provider down"))
                .when(delegate)
                .sendAlreadyRegisteredNotification(RECIPIENT);

        assertThatCode(() -> {
            sender.sendAlreadyRegisteredNotification(RECIPIENT);
            drainQueued();
        }).doesNotThrowAnyException();

        verify(delegate, times(MAX_ATTEMPTS)).sendAlreadyRegisteredNotification(RECIPIENT);
        verify(retryScheduler, times(MAX_ATTEMPTS - 1)).schedule(any(Runnable.class), any(Instant.class));
        assertLogsExhaustionWithoutToken();
    }

    @Test
    void sendConfirmation_whenExecutorRejects_logsErrorAndNeverThrows() {
        Executor rejecting = command -> {
            throw new RejectedExecutionException("saturated");
        };
        AsyncRetryingMailSender saturatedSender = newSender(rejecting);

        assertThatCode(() -> saturatedSender.sendConfirmation(RECIPIENT, LINK)).doesNotThrowAnyException();

        verifyNoInteractions(delegate, retryScheduler);
        assertThat(appender.list)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage()).contains("rejected").contains(RECIPIENT);
                    assertThat(event.getFormattedMessage()).doesNotContain(TOKEN);
                });
    }

    @Test
    void sendConfirmation_whenRetrySchedulingFails_abandonsWithoutEscalating() {
        when(retryScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenThrow(new IllegalStateException("scheduler shut down"));
        doThrow(new MailSendException("provider down"))
                .when(delegate)
                .sendConfirmation(RECIPIENT, LINK);

        assertThatCode(() -> {
            sender.sendConfirmation(RECIPIENT, LINK);
            drainQueued();
        }).doesNotThrowAnyException();

        verify(delegate).sendConfirmation(RECIPIENT, LINK);
        assertThat(appender.list)
                .last()
                .satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
    }

    @Test
    void constructor_whenMaxAttemptsBelowOne_rejects() {
        assertThatThrownBy(() -> new AsyncRetryingMailSender(delegate, capturingExecutor, retryScheduler, 0, DELAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
    }

    private void assertLogsExhaustionWithoutToken() {
        assertThat(appender.list)
                .anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN))
                .last()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage())
                            .contains("permanently failed")
                            .contains(RECIPIENT)
                            .contains(String.valueOf(MAX_ATTEMPTS))
                            // C24: recipient yes, token no — and the delegate mock's
                            // invocation records must not leak through either.
                            .doesNotContain(TOKEN);
                });
    }
}
