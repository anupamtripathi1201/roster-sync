package com.moveinsync.rostersync.service;

import com.moveinsync.rostersync.config.AppProperties;
import com.moveinsync.rostersync.config.FailureInjector;
import com.moveinsync.rostersync.core.ChangeApplier;
import com.moveinsync.rostersync.core.EmployeeState;
import com.moveinsync.rostersync.core.Field;
import com.moveinsync.rostersync.core.RosterEvent;
import com.moveinsync.rostersync.domain.Employee;
import com.moveinsync.rostersync.exception.ApiException;
import com.moveinsync.rostersync.messaging.ChangeEvent;
import com.moveinsync.rostersync.messaging.Outbox;
import com.moveinsync.rostersync.observability.SyncMetrics;
import com.moveinsync.rostersync.repository.EmployeeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The write path. Each public method is ONE transaction that:
 *   1. locks the employee row (SELECT ... FOR UPDATE),
 *   2. asks the pure ChangeApplier what to do,
 *   3. writes the employee row AND the outbox row,
 *   4. commits both or neither.
 */
@Service
public class EmployeeChangeService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeChangeService.class);

    private final EmployeeRepository employees;
    private final ChangeApplier applier;
    private final Outbox outbox;
    private final LookupService lookup;
    private final SyncMetrics metrics;
    private final FailureInjector failures;
    private final Clock clock;
    private final int buckets;

    public EmployeeChangeService(EmployeeRepository employees, ChangeApplier applier, Outbox outbox,
                                 LookupService lookup, SyncMetrics metrics, FailureInjector failures, Clock clock,
                                 AppProperties props) {
        this.employees = employees;
        this.applier = applier;
        this.outbox = outbox;
        this.lookup = lookup;
        this.metrics = metrics;
        this.failures = failures;
        this.clock = clock;
        this.buckets = props.diffBuckets();
    }

    @Transactional
    public ChangeApplier.Result applyHrEvent(String tenantId, RosterEvent event, String source) {
        Optional<Employee> locked = employees.lock(tenantId, event.employeeId());
        ChangeApplier.Result r = applier.apply(locked.map(Employee::toState), event);

        if (!r.droppedFields().isEmpty()) {
            metrics.ownershipDropped(r.droppedFields().size());
            log.warn("ownership rule: ignored fields HR does not own employeeId={} fields={}",
                    event.employeeId(), r.droppedFields());
        }
        if (!r.applied()) {
            return r;
        }
        Instant now = clock.instant();
        boolean wasActive = locked.map(Employee::isActive).orElse(false);
        Employee e = locked.orElseGet(() -> Employee.newFor(tenantId, event.employeeId(), now));
        e.applyState(r.newState(), buckets, "HR_EVENT", now);
        employees.saveAndFlush(e);   // a racing first insert fails HERE on the unique key; ChangeExecutor retries

        failures.maybeFail(FailureInjector.Point.AFTER_EMPLOYEE_WRITE);

        ChangeEvent.Type type = ChangeEvent.Type.forHrChange(r, wasActive);
        outbox.record(tenantId, event.employeeId(), type, r.newState().hrVersion(), r.changedFields(), source);
        lookup.evictEmployeeAfterCommit(tenantId, event.employeeId());
        log.info("change applied employeeId={} version={} type={}", event.employeeId(), event.version(), type);
        return r;
    }

    @Transactional
    public boolean deactivateByReconciliation(String tenantId, String employeeId, Instant runStartedAt) {
        Optional<Employee> locked = employees.lock(tenantId, employeeId);
        if (locked.isEmpty() || !locked.get().isActive() || locked.get().getUpdatedAt().isAfter(runStartedAt)) {
            return false;
        }
        Employee e = locked.get();
        EmployeeState s = e.toState();
        e.applyState(new EmployeeState(employeeId, s.fields(), s.hrVersion(), false), buckets, "RECONCILIATION",
                clock.instant());
        employees.saveAndFlush(e);
        outbox.record(tenantId, employeeId, ChangeEvent.Type.EMPLOYEE_DEACTIVATED, s.hrVersion(), Set.of(), "RECONCILIATION");
        lookup.evictEmployeeAfterCommit(tenantId, employeeId);
        return true;
    }

    // Transport admin edit: only transport-owned fields change, the HR version is not affected
    @Transactional
    public ChangeApplier.Result applyTransportEdit(String tenantId, String employeeId, Map<Field, String> edit) {
        Employee e = employees.lock(tenantId, employeeId)
                .orElseThrow(() -> ApiException.notFound("EMPLOYEE_NOT_FOUND", "Employee not found"));
        ChangeApplier.Result r = applier.applyTransportEdit(e.toState(), edit);
        if (r.applied()) {
            e.applyState(r.newState(), buckets, null, clock.instant());
            employees.saveAndFlush(e);
            outbox.record(tenantId, employeeId, ChangeEvent.Type.TRANSPORT_PROFILE_UPDATED, e.getHrVersion(),
                    r.changedFields(), "TRANSPORT_ADMIN");
            lookup.evictEmployeeAfterCommit(tenantId, employeeId);
        }
        return r;
    }
}
