package com.moveinsync.rostersync.core;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.ObjLongConsumer;

import static com.moveinsync.rostersync.core.CoreRulesTest.hr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

//Snapshot parsing, the bucketed diff, the safety brake, and the 200k benchmark.
class ReconciliationCoreTest {

    @TempDir
    Path tmp;

    // Fake store: bucket -> (employeeId -> hash) of active employees.
    static final class MapStore implements BucketedDiffEngine.StoreReader {
        final int buckets;
        final Map<Integer, Map<String, Long>> data = new HashMap<>();

        MapStore(int buckets) { this.buckets = buckets; }

        void put(String id, Map<Field, String> fields) {
            data.computeIfAbsent(EmployeeState.bucketOf(id, buckets), b -> new HashMap<>())
                    .put(id, EmployeeState.hashHrFields(fields));
        }

        @Override
        public void forEachActive(int bucket, ObjLongConsumer<String> c) {
            data.getOrDefault(bucket, Map.of()).forEach(c::accept);
        }
    }

    static final class Recorder implements BucketedDiffEngine.Listener {
        final List<String> added = new ArrayList<>(), changed = new ArrayList<>(), missing = new ArrayList<>();
        public void onAdded(SnapshotRow r) { added.add(r.employeeId()); }
        public void onChanged(SnapshotRow r) { changed.add(r.employeeId()); }
        public void onMissing(String id) { missing.add(id); }
    }

    static SnapshotRow row(String id, long v, String office) {
        return new SnapshotRow(1, id, v, hr(id, office, "DAY"), false);
    }

    // ---------- parser ----------

    @Test
    void parsesFullLineAndRejectsTruncatedOne() {
        var ok = SnapshotRow.parse(2, "E1,7,Asha,asha@acme.example,+919876543210,M1,BLR1,DAY");
        assertThat(ok).isInstanceOfSatisfying(SnapshotRow.Ok.class, o -> {
            assertThat(o.row().version()).isEqualTo(7);
            assertThat(o.row().fields()).containsEntry(Field.OFFICE_CODE, "BLR1");
        });
        var cut = SnapshotRow.parse(120_001, "E120000,3,Asha,asha@acm");
        assertThat(cut).isInstanceOfSatisfying(SnapshotRow.Bad.class, b -> {
            assertThat(b.employeeId()).isEqualTo("E120000");
            assertThat(b.violations().get(0).code()).isEqualTo("MALFORMED_ROW");
        });
        assertThat(SnapshotRow.parse(2, "E1,seven,A,a@b.co,,,BLR1,DAY")).isInstanceOf(SnapshotRow.Bad.class);
        assertThat(SnapshotRow.isHeader(SnapshotRow.HEADER)).isTrue();
        assertThat(SnapshotRow.isHeader("id,name")).isFalse();
    }

    // ---------- diff ----------

    @Test
    void classifiesAddedChangedUnchangedAndMissing() {
        MapStore store = new MapStore(8);
        store.put("SAME", hr("SAME", "BLR1", "DAY"));
        store.put("MOVED", hr("MOVED", "BLR1", "DAY"));
        store.put("GONE", hr("GONE", "BLR1", "DAY"));
        Recorder rec = new Recorder();

        var stats = new BucketedDiffEngine(8, tmp).diff(
                List.of(row("SAME", 1, "BLR1"), row("MOVED", 2, "HYD1"), row("NEW", 1, "BLR1")).iterator(), store, rec);

        assertThat(rec.added).containsExactly("NEW");
        assertThat(rec.changed).containsExactly("MOVED");
        assertThat(rec.missing).containsExactly("GONE");
        assertThat(stats.unchanged()).isEqualTo(1);
        assertThat(stats.storeActive()).isEqualTo(3);
    }

    @Test
    void rejectedRowStillCountsAsPresentAndDuplicatesKeepHighestVersion() {
        MapStore store = new MapStore(4);
        store.put("TYPO", hr("TYPO", "BLR1", "DAY"));
        Recorder rec = new Recorder();
        var stats = new BucketedDiffEngine(4, tmp).diff(List.of(
                SnapshotRow.presence(2, "TYPO"),
                row("DUP", 1, "BLR1"), row("DUP", 5, "HYD1")).iterator(), store, rec);
        assertThat(rec.missing).isEmpty();
        assertThat(stats.skippedInvalid()).isEqualTo(1);
        assertThat(stats.duplicates()).isEqualTo(1);
        assertThat(rec.added).containsExactly("DUP");
    }

    @Test
    void emptyFileReportsEveryoneMissingAndTempFilesAreRemoved() throws Exception {
        MapStore store = new MapStore(4);
        for (int i = 0; i < 10; i++) {
            store.put("E" + i, hr("E" + i, "BLR1", "DAY"));
        }
        var stats = new BucketedDiffEngine(4, tmp).diff(List.<SnapshotRow>of().iterator(), store, new Recorder());
        assertThat(stats.missing()).isEqualTo(10);
        try (var left = Files.list(tmp)) {
            assertThat(left.count()).isZero();
        }
    }

    // ---------- safety brake ----------

    @Test
    void truncatedExportTripsTheBrake() {
        var v = new SafetyBrake(0.30).evaluate(200_000, 80_000);   // file cut at 120,000 of 200,000
        assertThat(v.tripped()).isTrue();
        assertThat(v.ratio()).isEqualTo(0.40);
    }

    @Test
    void brakeThresholdIsInclusiveAndConfigurable() {
        assertThat(new SafetyBrake(0.30).evaluate(1000, 300).tripped()).isTrue();
        assertThat(new SafetyBrake(0.30).evaluate(1000, 299).tripped()).isFalse();
        assertThat(new SafetyBrake(0.05).evaluate(1000, 60).tripped()).isTrue();
        assertThat(new SafetyBrake(0.30).evaluate(0, 0).tripped()).isFalse();
        assertThatThrownBy(() -> new SafetyBrake(0)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- benchmark ----------

    // "Diff a 200,000-row snapshot in under 60 seconds with bounded memory." Surefire runs with -Xmx512m.
    @Test
    @Tag("benchmark")
    void diffs200kRowsUnder60SecondsWithBoundedMemory() {
        final int rows = 200_000, buckets = 64;
        MapStore store = new MapStore(buckets);
        for (int i = 0; i < rows; i++) {
            store.put("EMP" + i, hr("EMP" + i, "BLR1", "DAY"));
        }
        // generated lazily: every 50th employee missing, every 100th changed, 1,000 joiners
        Iterator<SnapshotRow> file = new Iterator<>() {
            int i = 0;

            public boolean hasNext() {
                while (i < rows && i % 50 == 7) {
                    i++;
                }
                return i < rows + 1000;
            }

            public SnapshotRow next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                int k = i++;
                return k >= rows ? row("NEW" + k, 1, "BLR1") : row("EMP" + k, 2, k % 100 == 0 ? "HYD1" : "BLR1");
            }
        };

        long start = System.nanoTime();
        var stats = new BucketedDiffEngine(buckets, tmp).diff(file, store, new Recorder());
        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("[benchmark] diff rows=%d time=%d ms throughput=%.0f rows/s maxRowsInMemory=%d maxHeapMB=%d%n",
                stats.fileRows(), ms, stats.fileRows() * 1000.0 / Math.max(ms, 1), stats.maxBucketRows(),
                Runtime.getRuntime().maxMemory() / 1048576);

        assertThat(ms).isLessThan(60_000);
        assertThat(stats.missing()).isEqualTo(4000);
        assertThat(stats.changed()).isEqualTo(2000);
        assertThat(stats.added()).isEqualTo(1000);
        assertThat(stats.maxBucketRows()).isLessThan(2 * rows / buckets);   // one bucket, never the whole file
    }
}
