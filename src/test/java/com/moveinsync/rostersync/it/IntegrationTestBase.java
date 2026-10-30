package com.moveinsync.rostersync.it;

import com.moveinsync.rostersync.config.FailureInjector;
import com.moveinsync.rostersync.domain.Employee;
import com.moveinsync.rostersync.dto.Dtos.BatchResult;
import com.moveinsync.rostersync.dto.Dtos.EventRequest;
import com.moveinsync.rostersync.messaging.ChangeEvent;
import com.moveinsync.rostersync.messaging.EventPublisher;
import com.moveinsync.rostersync.messaging.Outbox;
import com.moveinsync.rostersync.repository.EmployeeRepository;
import com.moveinsync.rostersync.repository.OutboxRepository;
import com.moveinsync.rostersync.service.IngestService;
import com.moveinsync.rostersync.service.LookupService;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared Spring context on H2 (MySQL mode). Every test works in its own fresh tenant, so tests are
 * isolated without truncating tables, which also exercises tenant isolation all the time.
 */
@SpringBootTest
@ActiveProfiles("test")
abstract class IntegrationTestBase {

    /** Test-only downstream consumer that records every delivered change event. */
    @Component
    static class CapturingSubscriber implements EventPublisher.Subscriber {
        final List<ChangeEvent> received = new CopyOnWriteArrayList<>();

        public boolean supports(ChangeEvent.Type type) { return true; }

        public void handle(ChangeEvent e) { received.add(e); }

        List<ChangeEvent> forEmployee(String tenant, String id) {
            return received.stream().filter(e -> e.tenantId().equals(tenant) && e.employeeId().equals(id)).toList();
        }
    }

    @Autowired IngestService ingest;
    @Autowired EmployeeRepository employees;
    @Autowired OutboxRepository outboxRows;
    @Autowired Outbox outbox;
    @Autowired FailureInjector failures;
    @Autowired LookupService lookup;
    @Autowired CapturingSubscriber captured;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void disarm() {
        failures.disarmAll();
    }

    String newTenant() {
        String tenant = "t-" + UUID.randomUUID().toString().substring(0, 8);
        lookup.addOffice(tenant, "BLR1", "Bengaluru");
        lookup.addOffice(tenant, "HYD1", "Hyderabad");
        return tenant;
    }

    static EventRequest upsert(String type, String id, long version, String office, String shift) {
        return new EventRequest(type, id, version, Instant.now(), "Person " + id, id.toLowerCase() + "@acme.example",
                "+919876543210", null, office, shift, null, null);
    }

    static EventRequest create(String id, long version) {
        return upsert("CREATE", id, version, "BLR1", "DAY");
    }

    static EventRequest deactivate(String id, long version) {
        return new EventRequest("DEACTIVATE", id, version, Instant.now(), null, null, null, null, null, null, null, null);
    }

    BatchResult send(String tenant, EventRequest... events) {
        return ingest.ingest(tenant, List.of(events));
    }

    Employee employee(String tenant, String id) {
        return employees.findByTenantIdAndEmployeeId(tenant, id).orElseThrow();
    }

    /** Publish everything pending, ignoring backoff (stands in for "time passes"). */
    void drainOutbox() {
        Instant later = Instant.now().plusSeconds(3600);
        while (outbox.publishDue(later) > 0) {
            // until a pass publishes nothing
        }
    }

    /** Other tests leave unpublished rows (relay is off in tests); mark them so an armed failure hits OUR event. */
    void onlyThisEventPending(String tenant, String employeeId) {
        Long ours = outboxRows.findByTenantIdAndEmployeeIdOrderByIdAsc(tenant, employeeId).get(0).getId();
        jdbc.update("update outbox_event set published_at = current_timestamp where published_at is null and id <> ?", ours);
    }

    long tripWithBooking(String tenant, String employeeId, Instant departure) {
        jdbc.update("insert into cab_trip (tenant_id, office_code, departure_at, capacity, seats_booked) values (?, 'BLR1', ?, 4, 1)",
                tenant, java.sql.Timestamp.from(departure));
        Long trip = jdbc.queryForObject("select max(id) from cab_trip", Long.class);
        jdbc.update("insert into booking (tenant_id, employee_id, trip_id, status, updated_at) values (?, ?, ?, 'CONFIRMED', ?)",
                tenant, employeeId, trip, java.sql.Timestamp.from(Instant.now()));
        return trip;
    }

    int seatsBooked(long tripId) {
        return jdbc.queryForObject("select seats_booked from cab_trip where id = ?", Integer.class, tripId);
    }

    String bookingStatus(String tenant, String employeeId, long tripId) {
        return jdbc.queryForObject("select status from booking where tenant_id = ? and employee_id = ? and trip_id = ?",
                String.class, tenant, employeeId, tripId);
    }
}
