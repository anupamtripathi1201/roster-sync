package com.moveinsync.rostersync.it;

import com.moveinsync.rostersync.core.Field;
import com.moveinsync.rostersync.core.RecordValidator.Violation;
import com.moveinsync.rostersync.dto.Dtos.BatchResult;
import com.moveinsync.rostersync.dto.Dtos.EventRequest;
import com.moveinsync.rostersync.service.EmployeeChangeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Live ingest end to end: idempotency, ordering, ownership, per-record validation, no PII in logs. */
@ExtendWith(OutputCaptureExtension.class)
class IngestIT extends IntegrationTestBase {

    @Autowired
    EmployeeChangeService changes;

    /** Idempotency key = (tenant, employee, version). Replay must change no row and add no event. */
    @Test
    void replayingTheSameFeedTwiceChangesNothing() {
        String tenant = newTenant();
        List<EventRequest> feed = List.of(create("E1", 1), create("E2", 1), upsert("UPDATE", "E1", 2, "HYD1", "NIGHT"),
                deactivate("E2", 2), upsert("UPDATE", "E1", 3, "HYD1", "DAY"));

        BatchResult first = ingest.ingest(tenant, feed);
        var rowsBefore = jdbc.queryForList("select * from employee where tenant_id = ? order by employee_id", tenant);
        long eventsBefore = outboxRows.countByTenantId(tenant);
        BatchResult second = ingest.ingest(tenant, feed);

        assertThat(first.applied()).isEqualTo(5);
        assertThat(second.applied()).isZero();
        assertThat(second.duplicates() + second.stale()).isEqualTo(5);
        assertThat(jdbc.queryForList("select * from employee where tenant_id = ? order by employee_id", tenant))
                .isEqualTo(rowsBefore);   // every column, incl. updated_at and row_version
        assertThat(outboxRows.countByTenantId(tenant)).isEqualTo(eventsBefore);
    }

    @Test
    void transferV7BeforeShiftChangeV6EndsInNewOfficeWithV7Shift() {
        String tenant = newTenant();
        send(tenant, create("E1", 5));
        BatchResult r = send(tenant, upsert("UPDATE", "E1", 7, "HYD1", "NIGHT"), upsert("UPDATE", "E1", 6, "BLR1", "EVENING"));

        assertThat(r.applied()).isEqualTo(1);
        assertThat(r.stale()).isEqualTo(1);
        assertThat(employee(tenant, "E1").getOfficeCode()).isEqualTo("HYD1");
        assertThat(employee(tenant, "E1").getShiftCode()).isEqualTo("NIGHT");
        assertThat(employee(tenant, "E1").getHrVersion()).isEqualTo(7);
    }

    @Test
    void lateCreateAfterDeactivateDoesNotResurrect() {
        String tenant = newTenant();
        send(tenant, deactivate("E1", 3));
        assertThat(send(tenant, create("E1", 2)).stale()).isEqualTo(1);
        assertThat(employee(tenant, "E1").isActive()).isFalse();
    }

    @Test
    void hrUpdateNeverOverwritesPickupPointSetByTransportAdmin() {
        String tenant = newTenant();
        send(tenant, create("E1", 1));
        changes.applyTransportEdit(tenant, "E1", Map.of(Field.PICKUP_POINT, "Gate 4"));

        BatchResult r = send(tenant, new EventRequest("UPDATE", "E1", 2L, Instant.now(), "Person E1", "e1@acme.example",
                "+919876543210", null, "HYD1", "DAY", "HR says main road", null));

        assertThat(r.applied()).isEqualTo(1);
        assertThat(employee(tenant, "E1").getOfficeCode()).isEqualTo("HYD1");
        assertThat(employee(tenant, "E1").getPickupPoint()).isEqualTo("Gate 4");
        assertThat(r.ownershipIgnored()).singleElement()
                .satisfies(n -> assertThat(n.ignoredFields()).containsExactly("PICKUP_POINT"));
        assertThat(employee(tenant, "E1").getHrVersion()).isEqualTo(2);   // the admin edit never bumped it
    }

    @Test
    void badRecordsAreReportedPerRecordAndNeverFailTheBatch() {
        String tenant = newTenant();
        EventRequest badPhone = new EventRequest("CREATE", "E2", 1L, Instant.now(), "Bad Phone", "e2@acme.example",
                "12345", null, "BLR1", "DAY", null, null);
        EventRequest badType = new EventRequest("TELEPORT", "E4", 1L, null, null, null, null, null, null, null, null, null);

        BatchResult r = send(tenant, create("E1", 1), badPhone, upsert("CREATE", "E3", 1, "NOPE", "DAY"), badType, create("E5", 1));

        assertThat(r.applied()).isEqualTo(2);
        assertThat(r.rejected()).isEqualTo(3);
        assertThat(r.errors()).extracting(BatchResult.RecordError::index).containsExactly(1, 2, 3);
        assertThat(r.errors().get(0).violations()).containsExactly(new Violation("PHONE", "INVALID_FORMAT"));
        assertThat(r.errors().get(1).violations()).containsExactly(new Violation("OFFICE_CODE", "UNKNOWN_OFFICE"));
        assertThat(employees.findByTenantIdAndEmployeeId(tenant, "E5")).isPresent();
    }

    @Test
    void sameVersionDifferentContentIsRejectedAsConflict() {
        String tenant = newTenant();
        send(tenant, create("E1", 1));
        BatchResult r = send(tenant, upsert("UPDATE", "E1", 1, "HYD1", "DAY"));
        assertThat(r.errors()).singleElement().satisfies(e ->
                assertThat(e.violations()).containsExactly(new Violation("version", "VERSION_CONFLICT")));
        assertThat(employee(tenant, "E1").getOfficeCode()).isEqualTo("BLR1");
    }

    /** "Never write personal data into logs or error reports." Canary values must appear nowhere. */
    @Test
    void noPersonalDataInLogsOrReports(CapturedOutput output) {
        String tenant = newTenant();
        String name = "Canaryname Zzyzx", email = "canary.zzyzx@acme.example", phone = "+919000011111", bad = "CANARY-PHONE";
        BatchResult r = send(tenant,
                new EventRequest("CREATE", "P1", 1L, Instant.now(), name, email, phone, null, "BLR1", "DAY", null, null),
                new EventRequest("CREATE", "P2", 1L, Instant.now(), name, email, bad, null, "BLR1", "DAY", null, null),
                new EventRequest("UPDATE", "P1", 2L, Instant.now(), name, email, phone, null, "HYD1", "DAY", "Canary lane", null),
                deactivate("P1", 3));
        drainOutbox();

        assertThat(output.getAll()).contains("P1").doesNotContain(name, email, phone, bad, "Canary lane");
        assertThat(r.toString()).doesNotContain(name, email, bad);
    }
}
