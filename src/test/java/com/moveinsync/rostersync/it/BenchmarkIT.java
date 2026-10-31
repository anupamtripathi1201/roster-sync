package com.moveinsync.rostersync.it;

import com.moveinsync.rostersync.core.EmployeeState;
import com.moveinsync.rostersync.core.Field;
import com.moveinsync.rostersync.core.SnapshotRow;
import com.moveinsync.rostersync.domain.ReconciliationRun.Status;
import com.moveinsync.rostersync.dto.Dtos.BatchResult;
import com.moveinsync.rostersync.dto.Dtos.RunView;
import com.moveinsync.rostersync.service.ReconciliationService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Measured performance. Each test prints a [benchmark] line for the README table. */
@Tag("benchmark")
class BenchmarkIT extends IntegrationTestBase {

    @Autowired
    ReconciliationService reconciliation;

    @TempDir
    Path tmp;

    /** 200,000 employees in the DB, a 200,000-row file on disk, full reconciliation under 60 s in 512 MB. */
    @Test
    void reconciles200kRowsEndToEndUnder60Seconds() throws Exception {
        final int n = 200_000;
        String tenant = newTenant();
        Timestamp now = Timestamp.from(Instant.now());
        List<Object[]> batch = new ArrayList<>(5000);
        for (int i = 0; i < n; i++) {
            String id = "S" + i;
            Map<Field, String> f = new EnumMap<>(Field.class);
            f.put(Field.NAME, "Person " + id);
            f.put(Field.EMAIL, id.toLowerCase() + "@acme.example");
            f.put(Field.PHONE, "+919876543210");
            f.put(Field.OFFICE_CODE, "BLR1");
            f.put(Field.SHIFT_CODE, "DAY");
            batch.add(new Object[]{tenant, id, f.get(Field.NAME), f.get(Field.EMAIL), f.get(Field.PHONE), "BLR1", "DAY", 1L,
                    EmployeeState.hashHrFields(f), EmployeeState.bucketOf(id, 64), true, now, now});
            if (batch.size() == 5000 || i == n - 1) {
                jdbc.batchUpdate("insert into employee (tenant_id, employee_id, name, email, phone, office_code, shift_code, "
                        + "hr_version, hr_hash, bucket, active, created_at, updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?)", batch);
                batch.clear();
            }
        }
        Path file = tmp.resolve("export.csv");
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            w.write(SnapshotRow.HEADER);
            w.newLine();
            for (int i = 0; i < n; i++) {
                w.write("S" + i + ",1,Person S" + i + ",s" + i + "@acme.example,+919876543210,,BLR1,DAY");
                w.newLine();
            }
        }

        long start = System.nanoTime();
        RunView run;
        try (InputStream in = Files.newInputStream(file)) {
            run = reconciliation.run(tenant, in);
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("[benchmark] reconciliation e2e rows=%d time=%d ms throughput=%.0f rows/s maxHeapMB=%d%n",
                run.fileRows(), ms, run.fileRows() * 1000.0 / Math.max(ms, 1), Runtime.getRuntime().maxMemory() / 1048576);

        assertThat(run.status()).isEqualTo(Status.COMPLETED);
        assertThat(run.unchanged()).isEqualTo(n);
        assertThat(ms).isLessThan(60_000);
    }

    /** Live ingest load: 8 client threads, 1,000 employees x 5 versions, one event per call. */
    @Test
    void ingestThroughputAndLatency() throws Exception {
        String tenant = newTenant();
        for (int i = 0; i < 200; i++) {
            send(tenant, create("W" + i, 1));   // warm-up
        }
        int threads = 8, people = 1000, versions = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<long[]>> futures = new ArrayList<>();
        long start = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            final int thread = t;
            futures.add(pool.submit(() -> {
                List<Long> lat = new ArrayList<>();
                for (int e = thread; e < people; e += threads) {
                    for (int v = 1; v <= versions; v++) {
                        long s = System.nanoTime();
                        BatchResult r = send(tenant, upsert(v == 1 ? "CREATE" : "UPDATE", "L" + e, v, v % 2 == 0 ? "HYD1" : "BLR1", "DAY"));
                        lat.add(System.nanoTime() - s);
                        assertThat(r.applied()).isEqualTo(1);
                    }
                }
                return lat.stream().mapToLong(Long::longValue).toArray();
            }));
        }
        List<Long> all = new ArrayList<>();
        for (Future<long[]> f : futures) {
            for (long l : f.get(300, TimeUnit.SECONDS)) {
                all.add(l);
            }
        }
        long totalMs = (System.nanoTime() - start) / 1_000_000;
        pool.shutdown();
        long[] sorted = all.stream().mapToLong(Long::longValue).sorted().toArray();
        double p50 = sorted[sorted.length / 2] / 1e6, p99 = sorted[(int) (sorted.length * 0.99)] / 1e6;
        System.out.printf("[benchmark] ingest events=%d threads=%d throughput=%.0f events/s p50=%.2f ms p99=%.2f ms cpus=%d%n",
                sorted.length, threads, sorted.length * 1000.0 / totalMs, p50, p99, Runtime.getRuntime().availableProcessors());

        assertThat(sorted.length).isEqualTo(people * versions);
        assertThat(p99).isLessThan(1000);
    }
}
