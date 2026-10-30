package com.moveinsync.rostersync.service;

import com.moveinsync.rostersync.config.AppProperties;
import com.moveinsync.rostersync.core.BucketedDiffEngine;
import com.moveinsync.rostersync.core.ChangeApplier;
import com.moveinsync.rostersync.core.RecordValidator.Violation;
import com.moveinsync.rostersync.core.RecordValidator;
import com.moveinsync.rostersync.core.RosterEvent;
import com.moveinsync.rostersync.core.SafetyBrake;
import com.moveinsync.rostersync.core.SnapshotRow;
import com.moveinsync.rostersync.domain.ReconciliationRun;
import com.moveinsync.rostersync.dto.Dtos.Rejection;
import com.moveinsync.rostersync.dto.Dtos.RunView;
import com.moveinsync.rostersync.exception.ApiException;
import com.moveinsync.rostersync.observability.SyncMetrics;
import com.moveinsync.rostersync.repository.ReconciliationRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final int BATCH = 1000;
    private static final int REJECTION_REPORT_LIMIT = 10_000;

    private final ReconciliationRunRepository runs;
    private final JdbcTemplate jdbc;
    private final RecordValidator validator;
    private final LookupService lookup;
    private final ChangeExecutor executor;
    private final EmployeeChangeService changes;
    private final SafetyBrake brake;
    private final SyncMetrics metrics;
    private final AppProperties props;
    private final Clock clock;

    public ReconciliationService(ReconciliationRunRepository runs, JdbcTemplate jdbc, RecordValidator validator,
                                 LookupService lookup, ChangeExecutor executor, EmployeeChangeService changes,
                                 SafetyBrake brake, SyncMetrics metrics, AppProperties props, Clock clock) {
        this.runs = runs;
        this.jdbc = jdbc;
        this.validator = validator;
        this.lookup = lookup;
        this.executor = executor;
        this.changes = changes;
        this.brake = brake;
        this.metrics = metrics;
        this.props = props;
        this.clock = clock;
    }

    public RunView run(String tenantId, InputStream csv) {
        if (runs.existsByTenantIdAndStatus(tenantId, ReconciliationRun.Status.RUNNING)) {
            throw ApiException.conflict("RECONCILIATION_IN_PROGRESS", "A reconciliation is already running");
        }
        Instant startedAt = clock.instant();
        ReconciliationRun run = runs.save(ReconciliationRun.start(tenantId, startedAt, props.brakeThreshold()));
        Listener listener = new Listener(tenantId, run.getId(), startedAt);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csv, StandardCharsets.UTF_8), 1 << 16)) {
            RowSource rows = new RowSource(reader, lookup.officeCodes(tenantId), listener);
            if (!SnapshotRow.isHeader(rows.readLine())) {
                run.fail("MISSING_OR_BAD_HEADER", clock.instant());
                return RunView.of(runs.save(run));
            }
            BucketedDiffEngine.StoreReader store = (bucket, consumer) -> jdbc.query(
                    "select employee_id, hr_hash from employee where tenant_id = ? and bucket = ? and active = true",
                    (RowCallbackHandler) rs -> consumer.accept(rs.getString(1), rs.getLong(2)), tenantId, bucket);
            BucketedDiffEngine.Stats stats = new BucketedDiffEngine(props.diffBuckets(),
                    Path.of(System.getProperty("java.io.tmpdir"))).diff(rows, store, listener);
            listener.flush();

            SafetyBrake.Verdict verdict = brake.evaluate(stats.storeActive(), stats.missing());
            run.recordDiff(stats, listener.rejected, listener.applied, listener.appliedNew, listener.stale, verdict);
            metrics.reconciliationFinished(stats.missing(), Duration.between(startedAt, clock.instant()));
            log.info("reconciliation diff runId={} fileRows={} changed={} added={} missing={} active={}", run.getId(),
                    stats.fileRows(), stats.changed(), stats.added(), stats.missing(), stats.storeActive());

            if (verdict.tripped()) {
                run.brake();
                runs.save(run);
                metrics.brakeTripped(verdict.missing());
                log.error("ALERT safety brake tripped runId={} missing={} active={} ratio={} threshold={} - nobody "
                                + "deactivated, operator approval required", run.getId(), verdict.missing(), verdict.active(),
                        String.format("%.3f", verdict.ratio()), verdict.threshold());
                return RunView.of(run);
            }
            run.complete(deactivatePending(tenantId, run.getId(), startedAt), clock.instant());
            return RunView.of(runs.save(run));
        } catch (IOException | RuntimeException e) {
            // nothing has been deactivated yet, so failing here is always safe
            log.error("reconciliation failed runId={} error={}", run.getId(), e.getClass().getSimpleName());
            run.fail("RUN_ERROR", clock.instant());
            return RunView.of(runs.save(run));
        }
    }

    public RunView approve(String tenantId, Long runId, String operator) {
        ReconciliationRun run = awaitingApproval(tenantId, runId);
        run.approve(operator, clock.instant());
        runs.save(run);
        log.warn("braked reconciliation approved runId={} by={}", runId, operator);
        run.complete(deactivatePending(tenantId, runId, run.getStartedAt()), clock.instant());
        return RunView.of(runs.save(run));
    }

    public RunView reject(String tenantId, Long runId, String operator) {
        ReconciliationRun run = awaitingApproval(tenantId, runId);
        run.reject(operator, clock.instant());
        log.info("braked reconciliation rejected runId={} by={}", runId, operator);
        return RunView.of(runs.save(run));
    }

    public RunView get(String tenantId, Long runId) {
        return RunView.of(load(tenantId, runId));
    }

    public List<Rejection> rejections(String tenantId, Long runId, int limit) {
        load(tenantId, runId);   // tenant check
        return jdbc.query("select line_number, employee_id, codes from reconciliation_rejection where run_id = ? "
                        + "order by id limit ?", (rs, i) -> new Rejection(rs.getLong(1), rs.getString(2), rs.getString(3)),
                runId, Math.min(Math.max(limit, 1), 1000));
    }

    // A run still RUNNING at startup was cut off by a crash. Nothing was deactivated yet; mark it failed.
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedRuns() {
        for (ReconciliationRun r : runs.findByStatus(ReconciliationRun.Status.RUNNING)) {
            r.fail("INTERRUPTED", clock.instant());
            runs.save(r);
        }
    }

    private ReconciliationRun load(String tenantId, Long runId) {
        return runs.findByIdAndTenantId(runId, tenantId)
                .orElseThrow(() -> ApiException.notFound("RUN_NOT_FOUND", "Reconciliation run not found"));
    }

    private ReconciliationRun awaitingApproval(String tenantId, Long runId) {
        ReconciliationRun run = load(tenantId, runId);
        if (run.getStatus() != ReconciliationRun.Status.AWAITING_APPROVAL) {
            throw ApiException.conflict("RUN_NOT_AWAITING_APPROVAL", "Run is not waiting for approval");
        }
        return run;
    }

    // Pages through pending ids by key (never all in memory); one transaction per employee.
    private long deactivatePending(String tenantId, Long runId, Instant runStartedAt) {
        long done = 0, afterId = 0;
        while (true) {
            List<Object[]> page = jdbc.query("select id, employee_id from pending_deactivation where run_id = ? and id > ? "
                    + "order by id limit 500", (rs, i) -> new Object[]{rs.getLong(1), rs.getString(2)}, runId, afterId);
            if (page.isEmpty()) {
                return done;
            }
            for (Object[] p : page) {
                String employeeId = (String) p[1];
                try {
                    if (executor.withRetry(() -> changes.deactivateByReconciliation(tenantId, employeeId, runStartedAt))) {
                        done++;
                    }
                } catch (RuntimeException e) {
                    log.error("deactivation failed runId={} employeeId={} error={}", runId, employeeId,
                            e.getClass().getSimpleName());
                }
                afterId = (Long) p[0];
            }
        }
    }

    private final class RowSource implements Iterator<SnapshotRow> {
        private final BufferedReader reader;
        private final Set<String> offices;
        private final Listener report;
        private long lineNumber;
        private SnapshotRow next;

        RowSource(BufferedReader reader, Set<String> offices, Listener report) {
            this.reader = reader;
            this.offices = offices;
            this.report = report;
        }

        String readLine() {
            try {
                String l = reader.readLine();
                if (l != null) {
                    lineNumber++;
                }
                return l;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public boolean hasNext() {
            while (next == null) {
                String line = readLine();
                if (line == null) {
                    return false;
                }
                if (!line.isBlank()) {
                    next = toRow(line);
                }
            }
            return true;
        }

        @Override
        public SnapshotRow next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            SnapshotRow r = next;
            next = null;
            return r;
        }

        private SnapshotRow toRow(String line) {
            SnapshotRow.Parsed parsed = SnapshotRow.parse(lineNumber, line);
            if (parsed instanceof SnapshotRow.Bad bad) {
                report.reject(bad.lineNumber(), bad.employeeId(), bad.violations());
                return presenceOrSkip(bad.lineNumber(), bad.employeeId());
            }
            SnapshotRow row = ((SnapshotRow.Ok) parsed).row();
            List<Violation> v = validator.validate(
                    new RecordValidator.Input(row.employeeId(), RosterEvent.Type.UPDATE, row.version(), row.fields()), offices);
            if (!v.isEmpty()) {
                report.reject(row.lineNumber(), row.employeeId(), v);
                return presenceOrSkip(row.lineNumber(), row.employeeId());
            }
            return row;
        }

        /** null means "skip this line entirely" (hasNext keeps reading). */
        private SnapshotRow presenceOrSkip(long line, String employeeId) {
            return employeeId != null && RecordValidator.ID.matcher(employeeId).matches()
                    ? SnapshotRow.presence(line, employeeId) : null;
        }
    }

    // Applies adds/changes as the diff streams them; buffers missing ids and rejections to the DB in batches.
    private final class Listener implements BucketedDiffEngine.Listener {
        private final String tenantId;
        private final Long runId;
        private final Instant startedAt;
        private final List<Object[]> pending = new ArrayList<>(BATCH);
        private final List<Object[]> rejections = new ArrayList<>(BATCH);
        long applied, appliedNew, stale, rejected;

        Listener(String tenantId, Long runId, Instant startedAt) {
            this.tenantId = tenantId;
            this.runId = runId;
            this.startedAt = startedAt;
        }

        @Override
        public void onAdded(SnapshotRow row) {
            if (apply(row)) {
                appliedNew++;
            }
        }

        @Override
        public void onChanged(SnapshotRow row) {
            apply(row);
        }

        @Override
        public void onMissing(String employeeId) {
            pending.add(new Object[]{runId, tenantId, employeeId});
            if (pending.size() >= BATCH) {
                flush();
            }
        }

        private boolean apply(SnapshotRow row) {
            try {
                ChangeApplier.Result r = executor.apply(tenantId,
                        new RosterEvent.Update(row.employeeId(), row.version(), startedAt, row.fields()), "RECONCILIATION");
                switch (r.decision()) {
                    case APPLIED -> applied++;
                    case STALE -> stale++;
                    case VERSION_CONFLICT -> reject(row.lineNumber(), row.employeeId(),
                            List.of(new Violation("version", "VERSION_CONFLICT")));
                    case DUPLICATE -> { }
                }
                return r.applied();
            } catch (RuntimeException e) {
                reject(row.lineNumber(), row.employeeId(), List.of(Violation.record("PROCESSING_ERROR")));
                log.error("reconciliation row failed runId={} employeeId={} error={}", runId, row.employeeId(),
                        e.getClass().getSimpleName());
                return false;
            }
        }

        //Per-row error report: codes only, stored up to a limit, always counted.
        void reject(long line, String employeeId, List<Violation> v) {
            rejected++;
            if (rejected > REJECTION_REPORT_LIMIT) {
                return;
            }
            String id = employeeId == null ? null : employeeId.substring(0, Math.min(64, employeeId.length()));
            String codes = v.stream().map(Violation::message).collect(Collectors.joining(";"));
            rejections.add(new Object[]{runId, line, id, codes.length() > 500 ? codes.substring(0, 500) : codes});
            if (rejections.size() >= BATCH) {
                flush();
            }
        }

        void flush() {
            if (!pending.isEmpty()) {
                jdbc.batchUpdate("insert into pending_deactivation (run_id, tenant_id, employee_id) values (?, ?, ?)", pending);
                pending.clear();
            }
            if (!rejections.isEmpty()) {
                jdbc.batchUpdate("insert into reconciliation_rejection (run_id, line_number, employee_id, codes) "
                        + "values (?, ?, ?, ?)", rejections);
                rejections.clear();
            }
        }
    }
}
