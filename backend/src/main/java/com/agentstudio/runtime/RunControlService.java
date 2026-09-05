package com.agentstudio.runtime;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

@Service
public class RunControlService {
    private final ConcurrentHashMap<String, Control> controls = new ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("run-deadline-", 0).factory());

    public void register(String runId, Thread thread, Duration timeout) {
        var control = new Control(thread);
        var previous = controls.putIfAbsent(runId, control);
        if (previous != null) throw new IllegalStateException("运行控制已注册：" + runId);
        control.deadline = scheduler.schedule(
                () -> terminate(runId, new RunTermination("TIMED_OUT", "运行超过总时限 " + timeout.toSeconds() + " 秒")),
                timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public boolean cancel(String runId, String reason) {
        return terminate(runId, new RunTermination("CANCELLED", reason));
    }

    public void check(String runId) {
        var termination = termination(runId);
        if (termination != null) throw new RunTerminatedException(termination);
    }

    public RunTermination termination(String runId) {
        var control = controls.get(runId);
        return control == null ? null : control.termination.get();
    }

    public void unregister(String runId) {
        var control = controls.remove(runId);
        if (control != null && control.deadline != null) control.deadline.cancel(false);
        Thread.interrupted();
    }

    private boolean terminate(String runId, RunTermination termination) {
        var control = controls.get(runId);
        if (control == null || !control.termination.compareAndSet(null, termination)) return false;
        control.thread.interrupt();
        return true;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private static final class Control {
        private final Thread thread;
        private final AtomicReference<RunTermination> termination = new AtomicReference<>();
        private volatile ScheduledFuture<?> deadline;

        private Control(Thread thread) {
            this.thread = thread;
        }
    }
}
