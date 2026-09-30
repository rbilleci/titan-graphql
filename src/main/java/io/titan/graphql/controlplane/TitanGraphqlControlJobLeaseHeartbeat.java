package io.titan.graphql.controlplane;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class TitanGraphqlControlJobLeaseHeartbeat implements AutoCloseable {
    private final ScheduledExecutorService executor;
    private final AtomicReference<SQLException> failure;

    private TitanGraphqlControlJobLeaseHeartbeat(
            ScheduledExecutorService executor,
            AtomicReference<SQLException> failure
    ) {
        this.executor = executor;
        this.failure = failure;
    }

    static TitanGraphqlControlJobLeaseHeartbeat disabled() {
        return new TitanGraphqlControlJobLeaseHeartbeat(null, new AtomicReference<>());
    }

    static TitanGraphqlControlJobLeaseHeartbeat start(
            TitanGraphqlControlJobQueue queue,
            TitanGraphqlControlJobQueue.ClaimedJob job,
            Duration lease
    ) {
        AtomicReference<SQLException> failure = new AtomicReference<>();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "control-job-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = Math.max(1L, lease.toMillis() / 3L);
        executor.scheduleWithFixedDelay(() -> {
            if (failure.get() != null) {
                return;
            }
            try {
                if (!queue.renew(job, lease)) {
                    failure.compareAndSet(null, new SQLException("control job lease was lost"));
                }
            } catch (SQLException | RuntimeException renewalFailure) {
                failure.compareAndSet(null, new SQLException(
                        "control job lease could not be renewed", renewalFailure));
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        return new TitanGraphqlControlJobLeaseHeartbeat(executor, failure);
    }

    void verify() throws SQLException {
        SQLException renewalFailure = failure.get();
        if (renewalFailure != null) {
            throw renewalFailure;
        }
    }

    @Override
    public void close() {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5L, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                failure.compareAndSet(null, new SQLException("control job heartbeat did not stop"));
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            failure.compareAndSet(null, new SQLException("control job heartbeat was interrupted", interrupted));
        }
    }
}
