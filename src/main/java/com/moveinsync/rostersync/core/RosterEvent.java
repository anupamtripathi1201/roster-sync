package com.moveinsync.rostersync.core;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * A change pushed by the HR system. Each subclass decides what the employee should look like
 * after it (polymorphism instead of a switch).
 *
 * Events are STATE TRANSFER, not deltas: a create/update carries all HR-owned fields at that
 * version. That is what makes "ignore stale" safe: v7 already contains the v7 shift.
 */

public abstract sealed class RosterEvent permits RosterEvent.Create, RosterEvent.Update, RosterEvent.Deactivate {

    public enum Type { CREATE, UPDATE, DEACTIVATE }

    private final String employeeId;
    private final long version;
    private final Instant sourceTimestamp;
    private final Map<Field, String> payload;

    protected RosterEvent(String employeeId, long version, Instant sourceTimestamp, Map<Field, String> payload) {
        this.employeeId = employeeId;
        this.version = version;
        this.sourceTimestamp = sourceTimestamp == null ? Instant.EPOCH : sourceTimestamp;
        EnumMap<Field, String> copy = new EnumMap<>(Field.class);
        if (payload != null) {
            copy.putAll(payload);
        }
        this.payload = Collections.unmodifiableMap(copy);
    }

    public static RosterEvent of(Type type, String employeeId, long version, Instant ts, Map<Field, String> payload) {
        return switch (type) {
            case CREATE -> new Create(employeeId, version, ts, payload);
            case UPDATE -> new Update(employeeId, version, ts, payload);
            case DEACTIVATE -> new Deactivate(employeeId, version, ts);
        };
    }

    public String employeeId() { return employeeId; }
    public long version() { return version; }
    public Instant sourceTimestamp() { return sourceTimestamp; }
    public Map<Field, String> payload() { return payload; }

    /**
     * Idempotency key: (tenant, employee, version). The event carries no tenant; it comes from the
     * logged-in HR system. Enforced by locking the (tenant_id, employee_id) row and comparing the
     * stored hr_version, so no separate "seen events" table is needed.
     */
    public String idempotencyKey(String tenantId) {
        return tenantId + ":" + employeeId + "@" + version;
    }

    public abstract Type type();

    // The state this event wants, given the current one. Only HR-owned payload fields are used.
    public abstract EmployeeState proposeState(Optional<EmployeeState> current);

    // Keep every non-HR field from the current state, take HR fields from the payload.
    protected Map<Field, String> mergeHrFields(Optional<EmployeeState> current) {
        EnumMap<Field, String> merged = new EnumMap<>(Field.class);
        current.ifPresent(c -> c.fields().forEach((f, v) -> {
            if (!f.isOwnedBy(Field.Owner.HR)) {
                merged.put(f, v);
            }
        }));
        payload.forEach((f, v) -> {
            if (f.isOwnedBy(Field.Owner.HR)) {
                merged.put(f, v);
            }
        });
        return merged;
    }

    @Override
    public String toString() {
        return type() + "{" + employeeId + "@" + version + "}";
    }

    // New joiner. If the employee exists at a lower version, this re-activates them.
    public static final class Create extends RosterEvent {
        public Create(String id, long v, Instant ts, Map<Field, String> p) { super(id, v, ts, p); }
        @Override public Type type() { return Type.CREATE; }
        @Override public EmployeeState proposeState(Optional<EmployeeState> current) {
            return new EmployeeState(employeeId(), mergeHrFields(current), version(), true);
        }
    }

    //Transfer, shift change... An update that arrives before its create is applied as the first state.
    public static final class Update extends RosterEvent {
        public Update(String id, long v, Instant ts, Map<Field, String> p) { super(id, v, ts, p); }
        @Override public Type type() { return Type.UPDATE; }
        @Override public EmployeeState proposeState(Optional<EmployeeState> current) {
            return new EmployeeState(employeeId(), mergeHrFields(current), version(), true);
        }
    }

    // Exit. For an unknown employee it leaves a tombstone so a late, stale create cannot resurrect them.
    public static final class Deactivate extends RosterEvent {
        public Deactivate(String id, long v, Instant ts) { super(id, v, ts, Map.of()); }
        @Override public Type type() { return Type.DEACTIVATE; }
        @Override public EmployeeState proposeState(Optional<EmployeeState> current) {
            return new EmployeeState(employeeId(), current.map(EmployeeState::fields).orElse(Map.of()), version(), false);
        }
    }
}
