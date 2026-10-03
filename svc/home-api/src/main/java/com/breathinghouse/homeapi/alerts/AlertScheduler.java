package com.breathinghouse.homeapi.alerts;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(
        name = "home-api.alerts.scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(AlertScheduler.class);

    private final AlertConfigurationLoader configurationLoader;
    private final AlertEvaluationService evaluationService;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean();

    public AlertScheduler(
            AlertConfigurationLoader configurationLoader,
            AlertEvaluationService evaluationService) {
        this.configurationLoader = configurationLoader;
        this.evaluationService = evaluationService;
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "alert-evaluator");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PostConstruct
    public void start() {
        running.set(true);
        scheduleNext(Duration.ZERO);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        executor.shutdownNow();
    }

    private void scheduleNext(Duration delay) {
        if (!running.get()) {
            return;
        }
        executor.schedule(this::runCycle, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void runCycle() {
        try {
            configurationLoader.reloadIfChanged();
            AlertConfiguration.Validated configuration = configurationLoader.current();
            evaluationService.evaluate(configuration);
        } catch (RuntimeException ex) {
            log.error("periodic alert evaluation failed", ex);
        } finally {
            if (running.get()) {
                scheduleNext(configurationLoader.current().evaluationInterval());
            }
        }
    }
}
