package com.moveinsync.rostersync.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.rostersync.config.AppProperties;
import com.moveinsync.rostersync.config.FailureInjector;
import com.moveinsync.rostersync.core.Field;
import com.moveinsync.rostersync.domain.OutboxEvent;
import com.moveinsync.rostersync.observability.SyncMetrics;
import com.moveinsync.rostersync.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

@Component
public class Outbox {

    private static final Logger log = LoggerFactory.getLogger(Outbox.class);

    private final OutboxRepository repo;
    private final EventPublisher publisher;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final SyncMetrics metrics;
    private final FailureInjector failures;
    private final AppProperties props;
    private final Clock clock;

    public Outbox(OutboxRepository repo, EventPublisher publisher, ObjectMapper mapper, TransactionTemplate tx,
                  SyncMetrics metrics, FailureInjector failures, AppProperties props, Clock clock) {
        this.repo = repo;
        this.publisher = publisher;
        this.mapper = mapper;
        this.tx = tx;
        this.metrics = metrics;
        this.failures = failures;
        this.props = props;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String tenantId, String employeeId, ChangeEvent.Type type, long hrVersion,
                       Collection<Field> changedFields, String source) {
        Instant now = clock.instant();
        ChangeEvent event = new ChangeEvent(UUID.randomUUID().toString(), type, tenantId, employeeId, hrVersion,
                changedFields.stream().map(Field::name).sorted().toList(), source, now);
        try {
            repo.save(new OutboxEvent(event.eventId(), tenantId, employeeId, type.name(),
                    mapper.writeValueAsString(event), now));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise change event", e);
        }
    }

    @Scheduled(fixedDelayString = "${rostersync.relay-poll-ms:500}")
    public void scheduledRelay() {
        if (props.relayEnabled()) {
            publishDue(clock.instant());
        }
    }

    // how many events were published in this pass
    public synchronized int publishDue(Instant now) {
        int published = 0;
        for (OutboxEvent row : repo.findDue(now, PageRequest.of(0, 200))) {
            try {
                ChangeEvent event = mapper.readValue(row.getPayload(), ChangeEvent.class);
                failures.maybeFail(FailureInjector.Point.PUBLISH);
                publisher.publish(event);
                failures.maybeFail(FailureInjector.Point.AFTER_PUBLISH_BEFORE_MARK);
                tx.executeWithoutResult(s -> repo.findById(row.getId()).ifPresent(r -> r.markPublished(clock.instant())));
                metrics.outboxPublished();
                published++;
            } catch (RuntimeException | JsonProcessingException e) {
                String type = e.getClass().getSimpleName();
                tx.executeWithoutResult(s -> repo.findById(row.getId()).ifPresent(r -> r.markFailed(clock.instant(), type)));
                metrics.outboxFailure();
                log.warn("outbox publish failed eventId={} attempts={} error={}", row.getEventId(), row.getAttempts() + 1, type);
            }
        }
        return published;
    }
}
