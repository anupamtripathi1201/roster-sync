package com.moveinsync.rostersync.service;

import com.moveinsync.rostersync.core.ChangeApplier;
import com.moveinsync.rostersync.core.RosterEvent;
import com.moveinsync.rostersync.observability.SyncMetrics;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class ChangeExecutor {

    private static final int MAX_ATTEMPTS = 3;
    private final EmployeeChangeService changes;
    private final SyncMetrics metrics;

    public ChangeExecutor(EmployeeChangeService changes, SyncMetrics metrics) {
        this.changes = changes;
        this.metrics = metrics;
    }

    public ChangeApplier.Result apply(String tenantId, RosterEvent event, String source) {
        metrics.ingestLag(event.sourceTimestamp());
        return metrics.applyLatency().record(() -> withRetry(() -> changes.applyHrEvent(tenantId, event, source)));
    }

    public <T> T withRetry(Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                metrics.applyRetry();
                try {
                    Thread.sleep(10L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
