package com.moveinsync.rostersync.it;

import com.moveinsync.rostersync.core.SnapshotRow;
import com.moveinsync.rostersync.domain.ReconciliationRun.Status;
import com.moveinsync.rostersync.dto.Dtos.EventRequest;
import com.moveinsync.rostersync.dto.Dtos.RunView;
import com.moveinsync.rostersync.service.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationIT extends IntegrationTestBase {

    @Autowired
    ReconciliationService reconciliation;

    private String tenantWithActive(int n) {
        String tenant = newTenant();
        List<EventRequest> batch = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            batch.add(create("E" + i, 1));
        }
        ingest.ingest(tenant, batch);
        return tenant;
    }

    private static String line(String id, long v, String office) {
        return id + "," + v + ",Person " + id + "," + id.toLowerCase() + "@acme.example,+919876543210,," + office + ",DAY";
    }

    private RunView runWith(String tenant, List<String> lines) {
        String csv = SnapshotRow.HEADER + "\n" + String.join("\n", lines) + "\n";
        return reconciliation.run(tenant, new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    private long active(String tenant) {
        return employees.countByTenantIdAndActiveTrue(tenant);
    }

    /** Case study: a cut-off file -> brake, nobody deactivated, operator sees how many look missing. */
    @Test
    void truncatedFileTripsBrakeAndDeactivatesNobody() {
        String tenant = tenantWithActive(100);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            lines.add(line("E" + i, 1, "BLR1"));
        }
        lines.add("E60,1,Person E60,e60@acm");   // the half-written last line

        RunView run = runWith(tenant, lines);

        assertThat(run.status()).isEqualTo(Status.AWAITING_APPROVAL);
        assertThat(run.activeBefore()).isEqualTo(100);
        assertThat(run.missingFromFile()).isEqualTo(39);   // E60's broken line still proves E60 is present
        assertThat(run.deactivated()).isZero();
        assertThat(active(tenant)).isEqualTo(100);
        assertThat(reconciliation.rejections(tenant, run.runId(), 10)).singleElement()
                .satisfies(r -> assertThat(r.codes()).contains("MALFORMED_ROW"));
    }

    @Test
    void operatorApprovalDeactivatesAndRejectionDoesNot() {
        String a = tenantWithActive(10);
        RunView approved = reconciliation.approve(a, runWith(a, List.of(line("E0", 1, "BLR1"), line("E1", 1, "BLR1"))).runId(), "ops");
        assertThat(approved.status()).isEqualTo(Status.COMPLETED);
        assertThat(approved.deactivated()).isEqualTo(8);
        assertThat(approved.decidedBy()).isEqualTo("ops");
        assertThat(active(a)).isEqualTo(2);

        String b = tenantWithActive(10);
        RunView rejected = reconciliation.reject(b, runWith(b, List.of(line("E0", 1, "BLR1"))).runId(), "ops");
        assertThat(rejected.status()).isEqualTo(Status.REJECTED);
        assertThat(active(b)).isEqualTo(10);
    }

    @Test
    void healthyFileAppliesChangesAddsJoinersAndDeactivatesLeavers() {
        String tenant = tenantWithActive(20);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 18; i++) {                       // E18, E19 left: 10%, under the threshold
            lines.add(i == 3 ? line("E3", 2, "HYD1") : line("E" + i, 1, "BLR1"));   // E3 transferred
        }
        lines.add(line("NEW1", 1, "BLR1"));

        RunView run = runWith(tenant, lines);

        assertThat(run.status()).isEqualTo(Status.COMPLETED);
        assertThat(run.changed()).isEqualTo(1);
        assertThat(run.added()).isEqualTo(1);
        assertThat(run.deactivated()).isEqualTo(2);
        assertThat(employee(tenant, "E3").getOfficeCode()).isEqualTo("HYD1");
        assertThat(employee(tenant, "E19").isActive()).isFalse();
        assertThat(employee(tenant, "E19").getDeactivationSource()).isEqualTo("RECONCILIATION");
        assertThat(employee(tenant, "NEW1").isActive()).isTrue();
    }

    @Test
    void rowWithBadDataIsReportedButNobodyIsDeactivatedForIt() {
        String tenant = tenantWithActive(5);
        RunView run = runWith(tenant, List.of(line("E0", 1, "BLR1"), line("E1", 1, "BLR1"), line("E2", 1, "BLR1"),
                line("E3", 1, "BLR1"), "E4,2,Person E4,e4@acme.example,BAD-PHONE,,BLR1,DAY"));
        assertThat(run.status()).isEqualTo(Status.COMPLETED);
        assertThat(run.rejectedRows()).isEqualTo(1);
        assertThat(run.missingFromFile()).isZero();
        assertThat(employee(tenant, "E4").isActive()).isTrue();
        assertThat(employee(tenant, "E4").getHrVersion()).isEqualTo(1);
    }

    @Test
    void staleExportRowCannotUndoNewerLiveEvent() {
        String tenant = tenantWithActive(3);
        send(tenant, upsert("UPDATE", "E1", 5, "HYD1", "DAY"));
        RunView run = runWith(tenant, List.of(line("E0", 1, "BLR1"), line("E1", 4, "BLR1"), line("E2", 1, "BLR1")));
        assertThat(run.stale()).isEqualTo(1);
        assertThat(employee(tenant, "E1").getOfficeCode()).isEqualTo("HYD1");
    }

    @Test
    void replayingTheSameFileIsANoOp() {
        String tenant = tenantWithActive(3);
        List<String> lines = List.of(line("E0", 1, "BLR1"), line("E1", 2, "HYD1"), line("E2", 1, "BLR1"));
        runWith(tenant, lines);
        long events = outboxRows.countByTenantId(tenant);
        RunView second = runWith(tenant, lines);
        assertThat(second.changed() + second.added()).isZero();
        assertThat(outboxRows.countByTenantId(tenant)).isEqualTo(events);
    }

    @Test
    void fileWithoutHeaderFailsSafely() {
        String tenant = tenantWithActive(3);
        RunView run = reconciliation.run(tenant, new ByteArrayInputStream("garbage\n".getBytes(StandardCharsets.UTF_8)));
        assertThat(run.status()).isEqualTo(Status.FAILED);
        assertThat(active(tenant)).isEqualTo(3);
    }
}
