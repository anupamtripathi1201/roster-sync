package com.moveinsync.rostersync.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.ObjLongConsumer;

/**
 * Compares the nightly export with the store without loading either fully into memory.
 *
 * Phase 1 (stream the file once): hash each employee id into one of N buckets, append the row to
 *   that bucket's temp file.
 * Phase 2 (one bucket at a time): load that bucket's rows into a map (~rows / N), stream the store's
 *   (employeeId, hash) for the same bucket, classify unchanged / changed / missing; leftovers are added.
 *
 * Time O(F + S). Memory O(F / N) rows plus N write buffers. Disk O(F).
 */
public final class BucketedDiffEngine {

    // Streams (employeeId, hrHash) of ACTIVE employees in one bucket. JDBC in production, a map in tests.
    public interface StoreReader {
        void forEachActive(int bucket, ObjLongConsumer<String> consumer);
    }

    // Callbacks while diffing. Missing employees are only reported; the safety brake decides.
    public interface Listener {
        void onAdded(SnapshotRow row);

        void onChanged(SnapshotRow row);

        void onMissing(String employeeId);
    }

    // storeActive is the brake's denominator; maxBucketRows is the proof of bounded memory.
    public record Stats(long fileRows, long added, long changed, long unchanged, long missing,
                        long skippedInvalid, long duplicates, long storeActive, int maxBucketRows) {
    }

    private static final char SEP = '\u001F';
    private final int buckets;
    private final Path workDir;

    public BucketedDiffEngine(int buckets, Path workDir) {
        if (buckets < 1) {
            throw new IllegalArgumentException("buckets must be >= 1");
        }
        this.buckets = buckets;
        this.workDir = workDir;
    }

    public Stats diff(Iterator<SnapshotRow> rows, StoreReader store, Listener listener) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory(workDir, "recon-");
            long fileRows = spill(rows, dir);
            return compare(dir, fileRows, store, listener);
        } catch (IOException e) {
            throw new UncheckedIOException("reconciliation spill failed", e);
        } finally {
            deleteQuietly(dir);
        }
    }

    private long spill(Iterator<SnapshotRow> rows, Path dir) throws IOException {
        BufferedWriter[] writers = new BufferedWriter[buckets];
        long count = 0;
        try {
            while (rows.hasNext()) {
                SnapshotRow r = rows.next();
                int b = EmployeeState.bucketOf(r.employeeId(), buckets);
                if (writers[b] == null) {
                    writers[b] = Files.newBufferedWriter(dir.resolve(b + ".tmp"), StandardCharsets.UTF_8);
                }
                writers[b].write(encode(r));
                writers[b].newLine();
                count++;
            }
        } finally {
            for (BufferedWriter w : writers) {
                if (w != null) {
                    w.close();
                }
            }
        }
        return count;
    }

    private Stats compare(Path dir, long fileRows, StoreReader store, Listener listener) throws IOException {
        long[] t = new long[7]; // added, changed, unchanged, missing, skipped, duplicates, storeActive
        int maxBucketRows = 0;
        for (int b = 0; b < buckets; b++) {
            Map<String, SnapshotRow> bucket = new HashMap<>();
            Path file = dir.resolve(b + ".tmp");
            if (Files.exists(file)) {
                try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    for (String line; (line = r.readLine()) != null; ) {
                        SnapshotRow row = decode(line);
                        SnapshotRow prev = bucket.get(row.employeeId());
                        if (prev != null) {
                            t[5]++;
                            if (!wins(row, prev)) {
                                continue;
                            }
                        }
                        bucket.put(row.employeeId(), row);
                    }
                }
            }
            maxBucketRows = Math.max(maxBucketRows, bucket.size());
            store.forEachActive(b, (employeeId, storedHash) -> {
                t[6]++;
                SnapshotRow row = bucket.remove(employeeId);
                if (row == null) {
                    t[3]++;
                    listener.onMissing(employeeId);
                } else if (row.presenceOnly()) {
                    t[4]++;
                } else if (row.hrHash() != storedHash) {
                    t[1]++;
                    listener.onChanged(row);
                } else {
                    t[2]++;
                }
            });
            for (SnapshotRow row : bucket.values()) {
                if (row.presenceOnly()) {
                    t[4]++;
                } else {
                    t[0]++;
                    listener.onAdded(row);
                }
            }
        }
        return new Stats(fileRows, t[0], t[1], t[2], t[3], t[4], t[5], t[6], maxBucketRows);
    }

    // Same id twice in one file: a valid row beats a rejected one, then the higher version wins.
    private static boolean wins(SnapshotRow candidate, SnapshotRow existing) {
        if (candidate.presenceOnly() != existing.presenceOnly()) {
            return existing.presenceOnly();
        }
        return candidate.version() > existing.version();
    }

    private static String encode(SnapshotRow r) {
        StringBuilder sb = new StringBuilder(160).append(r.lineNumber()).append(SEP).append(clean(r.employeeId()))
                .append(SEP).append(r.version()).append(SEP).append(r.presenceOnly() ? '1' : '0');
        for (Field f : Field.values()) {
            String v = r.fields().get(f);
            sb.append(SEP).append(v == null ? "" : clean(v));
        }
        return sb.toString();
    }

    private static SnapshotRow decode(String line) {
        String[] p = line.split(String.valueOf(SEP), -1);
        Map<Field, String> fields = new EnumMap<>(Field.class);
        Field[] all = Field.values();
        for (int i = 0; i < all.length; i++) {
            if (!p[4 + i].isEmpty()) {
                fields.put(all[i], p[4 + i]);
            }
        }
        return new SnapshotRow(Long.parseLong(p[0]), p[1], Long.parseLong(p[2]), fields, "1".equals(p[3]));
    }

    private static String clean(String v) {
        return v.replace(SEP, ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var files = Files.list(dir)) {
            files.forEach(f -> f.toFile().delete());
        } catch (IOException ignored) {
            // best-effort cleanup of temp files
        }
        dir.toFile().delete();
    }
}
