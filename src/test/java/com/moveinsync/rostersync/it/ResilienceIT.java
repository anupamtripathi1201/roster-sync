package com.moveinsync.rostersync.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.rostersync.config.FailureInjector;
import com.moveinsync.rostersync.core.RosterEvent;
import com.moveinsync.rostersync.domain.OutboxEvent;
import com.moveinsync.rostersync.dto.Dtos.BatchResult;
import com.moveinsync.rostersync.messaging.ChangeEvent;
import com.moveinsync.rostersync.messaging.EventPublisher;
import com.moveinsync.rostersync.service.EmployeeChangeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Concurrency, failure injection around the outbox, and leaver side effects. */
class ResilienceIT extends IntegrationTestBase {

    @Autowired
    EmployeeChangeService changes;
    @Autowired
    EventPublisher publisher;
    @Autowired
    ObjectMapper mapper;

    // ---------- concurrency ----------

    /**
     * 8 threads race 200 versions of ONE employee in random order. The row lock must serialise them:
     * the final version is the max and the outbox shows strictly increasing versions (no lost update).
     */
    @Test
    void concurrentOutOfOrderUpdatesConvergeToHighestVersion() throws Exception {
        String tenant = newTenant();
        send(tenant, create("E1", 0));
        List<Integer> versions = new ArrayList<>();
        for (int v = 1; v <= 200; v++) {
            versions.add(v);
        }
        Collections.shuffle(versions, new Random(42));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<BatchResult>> results = new ArrayList<>();
        for (int v : versions) {
            results.add(pool.submit(() -> {
                go.await();
                return send(tenant, upsert("UPDATE", "E1", v, v % 2 == 0 ? "HYD1" : "BLR1", "S" + v));
            }));
        }
        go.countDown();
        int applied = 0, rejected = 0;
        for (Future<BatchResult> f : results) {
            BatchResult r = f.get(60, TimeUnit.SECONDS);
            applied += r.applied();
            rejected += r.rejected();
        }
        pool.shutdown();

        assertThat(rejected).isZero();
        assertThat(employee(tenant, "E1").getHrVersion()).isEqualTo(200);
        assertThat(employee(tenant, "E1").getShiftCode()).isEqualTo("S200");
        List<OutboxEvent> events = outboxRows.findByTenantIdAndEmployeeIdOrderByIdAsc(tenant, "E1");
        assertThat(events).hasSize(applied + 1);   // + the create
        long previous = -1;
        for (OutboxEvent e : events) {
            long v = mapper.readValue(e.getPayload(), ChangeEvent.class).hrVersion();
            assertThat(v).isGreaterThan(previous);
            previous = v;
        }
    }

    /** Four first-ever creates race on the unique key: one wins, the retries see it and become duplicates. */
    @Test
    void racingFirstCreatesProduceExactlyOneRow() throws Exception {
        String tenant = newTenant();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<BatchResult>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return send(tenant, create("NEW1", 1));
            }));
        }
        go.countDown();
        int applied = 0, duplicates = 0;
        for (Future<BatchResult> f : results) {
            BatchResult r = f.get(30, TimeUnit.SECONDS);
            applied += r.applied();
            duplicates += r.duplicates();
        }
        pool.shutdown();
        assertThat(applied).isEqualTo(1);
        assertThat(duplicates).isEqualTo(3);
        assertThat(outboxRows.findByTenantIdAndEmployeeIdOrderByIdAsc(tenant, "NEW1")).hasSize(1);
    }

    // ---------- crash between the database write and the event publish ----------

    @Test
    void crashBeforeCommitLeavesNeitherRowNorEvent() {
        String tenant = newTenant();
        failures.arm(FailureInjector.Point.AFTER_EMPLOYEE_WRITE, 1);

        assertThatThrownBy(() -> changes.applyHrEvent(tenant,
                new RosterEvent.Create("E1", 1, Instant.now(), Map.of()), "TEST"))
                .isInstanceOf(FailureInjector.SimulatedCrash.class);
        assertThat(employees.findByTenantIdAndEmployeeId(tenant, "E1")).isEmpty();
        assertThat(outboxRows.findByTenantIdAndEmployeeIdOrderByIdAsc(tenant, "E1")).isEmpty();

        // HR redelivers after the "restart": applied exactly once
        assertThat(send(tenant, create("E1", 1)).applied()).isEqualTo(1);
        assertThat(outboxRows.findByTenantIdAndEmployeeIdOrderByIdAsc(tenant, "E1")).hasSize(1);
    }

    @Test
    void crashBeforePublishIsRecoveredFromTheOutbox() {
        String tenant = newTenant();
        send(tenant, create("E1", 1));
        onlyThisEventPending(tenant, "E1");
        failures.arm(FailureInjector.Point.PUBLISH, 1);

        outbox.publishDue(Instant.now());   // the attempt that "crashes"

        assertThat(outboxRows.findByTenantIdAndEmployeeIdOrderByIdAsc(tenant, "E1")).singleElement()
                .satisfies(o -> assertThat(o.getPublishedAt()).isNull());
        assertThat(employee(tenant, "E1").isActive()).isTrue();     // the change is in the DB...
        assertThat(captured.forEmployee(tenant, "E1")).isEmpty();   // ...and nobody has heard about it yet

        drainOutbox();                                              // relay after restart
        assertThat(captured.forEmployee(tenant, "E1")).hasSize(1);
    }

    @Test
    void crashAfterPublishRedeliversTheSameEventId() {
        String tenant = newTenant();
        send(tenant, create("E1", 1));
        onlyThisEventPending(tenant, "E1");
        failures.arm(FailureInjector.Point.AFTER_PUBLISH_BEFORE_MARK, 1);

        outbox.publishDue(Instant.now());
        drainOutbox();

        List<ChangeEvent> delivered = captured.forEmployee(tenant, "E1");
        assertThat(delivered).hasSize(2);
        assertThat(delivered.get(0).eventId()).isEqualTo(delivered.get(1).eventId());   // consumers dedupe on this
    }

    @Test
    void oneCrashingRecordDoesNotFailTheBatch() {
        String tenant = newTenant();
        failures.arm(FailureInjector.Point.AFTER_EMPLOYEE_WRITE, 1);
        BatchResult r = send(tenant, create("E1", 1), create("E2", 1), create("E3", 1));
        assertThat(r.applied()).isEqualTo(2);
        assertThat(r.errors()).singleElement().satisfies(e -> assertThat(e.employeeId()).isEqualTo("E1"));
    }

    // ---------- leaver side effects ----------

    @Test
    void leaverHasFutureBookingsCancelledAndSeatsFreedExactlyOnce() {
        String tenant = newTenant();
        send(tenant, create("E1", 1));
        Instant now = Instant.now();
        long future = tripWithBooking(tenant, "E1", now.plus(Duration.ofDays(1)));
        long past = tripWithBooking(tenant, "E1", now.minus(Duration.ofDays(1)));

        send(tenant, deactivate("E1", 2));
        drainOutbox();

        assertThat(bookingStatus(tenant, "E1", future)).isEqualTo("CANCELLED");
        assertThat(bookingStatus(tenant, "E1", past)).isEqualTo("CONFIRMED");
        assertThat(seatsBooked(future)).isZero();
        assertThat(seatsBooked(past)).isEqualTo(1);

        // at-least-once: the same event again must not free a seat twice
        publisher.publish(new ChangeEvent("dup", ChangeEvent.Type.EMPLOYEE_DEACTIVATED, tenant, "E1", 2, List.of(), "TEST", now));
        assertThat(seatsBooked(future)).isZero();
    }
}
