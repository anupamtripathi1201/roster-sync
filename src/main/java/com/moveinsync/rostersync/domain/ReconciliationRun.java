package com.moveinsync.rostersync.domain;

import com.moveinsync.rostersync.core.BucketedDiffEngine;
import com.moveinsync.rostersync.core.SafetyBrake;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

//One nightly reconciliation. Status changes are methods, so an illegal jump throws.
@Entity
@Table(name = "reconciliation_run")
public class ReconciliationRun {

    public enum Status {
        RUNNING,
        /** Safety brake tripped: nobody deactivated; an operator must approve or reject. */
        AWAITING_APPROVAL,
        COMPLETED,
        REJECTED,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;
    @Column(name = "finished_at")
    private Instant finishedAt;
    @Column(name = "file_rows")
    private long fileRows;
    @Column(name = "rejected_rows")
    private long rejectedRows;
    private long added;
    private long changed;
    private long unchanged;
    private long stale;
    private long missing;
    @Column(name = "store_active")
    private long storeActive;
    @Column(name = "missing_ratio")
    private double missingRatio;
    private double threshold;
    private long deactivated;
    @Column(name = "decided_by")
    private String decidedBy;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Column(name = "failure_code")
    private String failureCode;

    protected ReconciliationRun() {
    }

    public static ReconciliationRun start(String tenantId, Instant now, double threshold) {
        ReconciliationRun r = new ReconciliationRun();
        r.tenantId = tenantId;
        r.status = Status.RUNNING;
        r.startedAt = now;
        r.threshold = threshold;
        return r;
    }

    public void recordDiff(BucketedDiffEngine.Stats s, long rejected, long applied, long appliedNew, long staleRows,
                           SafetyBrake.Verdict v) {
        fileRows = s.fileRows();
        rejectedRows = rejected;
        added = appliedNew;
        changed = applied - appliedNew;
        unchanged = s.unchanged();
        stale = staleRows;
        missing = s.missing();
        storeActive = s.storeActive();
        missingRatio = v.ratio();
    }

    public void brake() {
        require(Status.RUNNING);
        status = Status.AWAITING_APPROVAL;
    }

    public void approve(String operator, Instant now) {
        require(Status.AWAITING_APPROVAL);
        decidedBy = operator;
        decidedAt = now;
        status = Status.RUNNING;
    }

    public void reject(String operator, Instant now) {
        require(Status.AWAITING_APPROVAL);
        decidedBy = operator;
        decidedAt = now;
        status = Status.REJECTED;
        finishedAt = now;
    }

    public void complete(long deactivatedCount, Instant now) {
        require(Status.RUNNING);
        deactivated = deactivatedCount;
        status = Status.COMPLETED;
        finishedAt = now;
    }

    public void fail(String code, Instant now) {
        status = Status.FAILED;
        failureCode = code;
        finishedAt = now;
    }

    private void require(Status expected) {
        if (status != expected) {
            throw new IllegalStateException("run " + id + " is " + status + ", expected " + expected);
        }
    }

    public Long getId() { return id; }
    public Status getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public long getFileRows() { return fileRows; }
    public long getRejectedRows() { return rejectedRows; }
    public long getAdded() { return added; }
    public long getChanged() { return changed; }
    public long getUnchanged() { return unchanged; }
    public long getStale() { return stale; }
    public long getMissing() { return missing; }
    public long getStoreActive() { return storeActive; }
    public double getMissingRatio() { return missingRatio; }
    public double getThreshold() { return threshold; }
    public long getDeactivated() { return deactivated; }
    public String getDecidedBy() { return decidedBy; }
    public String getFailureCode() { return failureCode; }
}
