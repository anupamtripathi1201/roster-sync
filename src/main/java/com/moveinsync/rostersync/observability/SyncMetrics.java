package com.moveinsync.rostersync.observability;

import com.moveinsync.rostersync.core.ChangeApplier;
import com.moveinsync.rostersync.repository.OutboxRepository;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The counters on-call needs at 3 AM, under rostersync_* on /actuator/prometheus:
 *   events_received / events_processed{outcome} / events_rejected{reason}  -> volume, error rate, rejections
 *   ingest_lag (p50, p99)                                                  -> how far behind HR we are
 *   apply_retries, apply_latency                                           -> lock contention, write latency
 *   outbox_queue_depth, outbox_lag_seconds, outbox_publish_failures        -> downstream backlog and health
 *   reconciliation_brake_tripped, reconciliation_last_missing              -> nightly file health
 */
@Component
public class SyncMetrics {

    private final MeterRegistry registry;
    private final Clock clock;
    private final Timer applyLatency;
    private final DistributionSummary ingestLag;
    private final AtomicLong lastMissing = new AtomicLong();

    public SyncMetrics(MeterRegistry registry, OutboxRepository outbox, Clock clock) {
        this.registry = registry;
        this.clock = clock;
        this.applyLatency = Timer.builder("rostersync.apply.latency").publishPercentiles(0.5, 0.99).register(registry);
        this.ingestLag = DistributionSummary.builder("rostersync.ingest.lag").baseUnit("ms")
                .publishPercentiles(0.5, 0.99).register(registry);
        Gauge.builder("rostersync.outbox.queue.depth", outbox, OutboxRepository::countByPublishedAtIsNull).register(registry);
        Gauge.builder("rostersync.outbox.lag.seconds", outbox, this::oldestPendingSeconds).register(registry);
        Gauge.builder("rostersync.reconciliation.last.missing", lastMissing, AtomicLong::get).register(registry);
    }

    public void received(String type) {
        registry.counter("rostersync.events.received", "type", type == null ? "UNKNOWN" : type).increment();
    }

    public void processed(ChangeApplier.Decision d) {
        registry.counter("rostersync.events.processed", "outcome", d.name()).increment();
    }

    public void rejected(String reason) {
        registry.counter("rostersync.events.rejected", "reason", reason).increment();
    }

    public void ingestLag(Instant sourceTimestamp) {
        if (sourceTimestamp != null && sourceTimestamp.isAfter(Instant.EPOCH)) {
            ingestLag.record(Math.max(0, Duration.between(sourceTimestamp, clock.instant()).toMillis()));
        }
    }

    public Timer applyLatency() {
        return applyLatency;
    }

    public void applyRetry() {
        registry.counter("rostersync.apply.retries").increment();
    }

    public void ownershipDropped(int fields) {
        registry.counter("rostersync.ownership.dropped.fields").increment(fields);
    }

    public void outboxPublished() {
        registry.counter("rostersync.outbox.published").increment();
    }

    public void outboxFailure() {
        registry.counter("rostersync.outbox.publish.failures").increment();
    }

    public void brakeTripped(long missing) {
        registry.counter("rostersync.reconciliation.brake.tripped").increment();
        lastMissing.set(missing);
    }

    public void reconciliationFinished(long missing, Duration took) {
        lastMissing.set(missing);
        registry.timer("rostersync.reconciliation.duration").record(took);
    }

    public void bookingsCancelled(int n) {
        registry.counter("rostersync.bookings.cancelled").increment(n);
    }

    private double oldestPendingSeconds(OutboxRepository repo) {
        try {
            return repo.findFirstByPublishedAtIsNullOrderByIdAsc()
                    .map(o -> (double) Duration.between(o.getCreatedAt(), clock.instant()).toSeconds()).orElse(0.0);
        } catch (RuntimeException e) {
            return Double.NaN;   // DB down: show a gap, not a misleading 0
        }
    }
}
