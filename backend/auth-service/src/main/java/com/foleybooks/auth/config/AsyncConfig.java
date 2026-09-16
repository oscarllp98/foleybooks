package com.foleybooks.auth.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Async infrastructure for mail dispatch (AU-09, ADR-001/D-02). A small
 * dedicated, bounded executor keeps SMTP work off request threads so
 * registration never blocks on delivery (LC-18); a separate one-thread
 * scheduler parks retries without holding a dispatch thread while backoff
 * elapses. Both drain gracefully on shutdown so in-flight confirmations get
 * a short window to finish. Mail is the only MVP use of async here; the
 * beans are named {@code mail*} to stay unambiguous (the {@code Executor}
 * return types keep Boot's {@code applicationTaskExecutor} conditional off).
 */
@Configuration(proxyBeanMethods = false)
public class AsyncConfig {

    private static final int MAIL_DISPATCH_THREADS = 2;
    private static final int MAIL_DISPATCH_QUEUE_CAPACITY = 200;
    private static final int SHUTDOWN_AWAIT_SECONDS = 10;

    @Bean
    public Executor mailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(MAIL_DISPATCH_THREADS);
        executor.setMaxPoolSize(MAIL_DISPATCH_THREADS);
        executor.setQueueCapacity(MAIL_DISPATCH_QUEUE_CAPACITY);
        executor.setThreadNamePrefix("mail-dispatch-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_SECONDS);
        return executor;
    }

    @Bean
    public TaskScheduler mailTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("mail-retry-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_SECONDS);
        return scheduler;
    }
}
