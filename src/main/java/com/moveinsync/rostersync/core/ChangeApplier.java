package com.moveinsync.rostersync.core;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure function: (current state, change) -> result. No database, no Spring.
 * The caller serialises calls per employee (row lock).
 */
public final class ChangeApplier {

    public enum Decision {
        /** Newer than anything seen: apply. */
        APPLIED,
        /** Older than the stored version: ignore. */
        STALE,
        /** Same version, same content: no-op. */
        DUPLICATE,
        /** Same version, different content: the source broke its contract. Reject and report. */
        VERSION_CONFLICT
    }

    /**
     * @param newState      state to persist (meaningful when APPLIED; otherwise the unchanged current state)
     * @param changedFields names of fields whose value changed (never the values)
     * @param droppedFields fields the writer sent but does not own; ignored by the ownership rule
     */
    public record Result(Decision decision, EmployeeState newState, Set<Field> changedFields,
                         Set<Field> droppedFields, boolean deactivated, boolean created) {
        public boolean applied() {
            return decision == Decision.APPLIED;
        }
    }

    /**
     * The version comparison rule, in one place:
     *   no stored record -> APPLIED;  incoming > stored -> APPLIED;  incoming < stored -> STALE;
     *   equal and same content -> DUPLICATE;  equal and different content -> VERSION_CONFLICT.
     */
    public static Decision decide(Long storedVersion, long incomingVersion, boolean sameContent) {
        if (storedVersion == null || incomingVersion > storedVersion) {
            return Decision.APPLIED;
        }
        if (incomingVersion < storedVersion) {
            return Decision.STALE;
        }
        return sameContent ? Decision.DUPLICATE : Decision.VERSION_CONFLICT;
    }

    // Apply an HR event under the version gate and the ownership rule.
    public Result apply(Optional<EmployeeState> current, RosterEvent event) {
        Set<Field> dropped = notOwnedBy(event.payload(), Field.Owner.HR);
        EmployeeState proposed = event.proposeState(current);
        boolean sameContent = current
                .map(c -> c.active() == proposed.active() && c.hrHash() == proposed.hrHash())
                .orElse(false);
        Decision d = decide(current.map(EmployeeState::hrVersion).orElse(null), event.version(), sameContent);
        if (d != Decision.APPLIED) {
            return new Result(d, current.orElse(null), Set.of(), dropped, false, false);
        }
        boolean wasActive = current.map(EmployeeState::active).orElse(false);
        return new Result(d, proposed, diff(current.map(EmployeeState::fields).orElse(Map.of()), proposed.fields()),
                dropped, wasActive && !proposed.active(), current.isEmpty());
    }

    /**
     * Apply a transport-admin edit. Only transport-owned fields change; HR fields and the HR version
     * are untouched, so an admin edit can never make a real HR event look stale.
     */
    public Result applyTransportEdit(EmployeeState current, Map<Field, String> edit) {
        Objects.requireNonNull(current, "current");
        Set<Field> dropped = notOwnedBy(edit, Field.Owner.TRANSPORT_ADMIN);
        EnumMap<Field, String> merged = new EnumMap<>(Field.class);
        merged.putAll(current.fields());
        edit.forEach((f, v) -> {
            if (f.isOwnedBy(Field.Owner.TRANSPORT_ADMIN)) {
                if (v == null || v.isBlank()) {
                    merged.remove(f);
                } else {
                    merged.put(f, v);
                }
            }
        });
        EmployeeState next = new EmployeeState(current.employeeId(), merged, current.hrVersion(), current.active());
        Set<Field> changed = diff(current.fields(), next.fields());
        return new Result(changed.isEmpty() ? Decision.DUPLICATE : Decision.APPLIED, next, changed, dropped, false, false);
    }

    private static Set<Field> notOwnedBy(Map<Field, String> payload, Field.Owner writer) {
        EnumSet<Field> out = EnumSet.noneOf(Field.class);
        payload.forEach((f, v) -> {
            if (!f.isOwnedBy(writer) && v != null) {
                out.add(f);
            }
        });
        return out;
    }

    private static Set<Field> diff(Map<Field, String> before, Map<Field, String> after) {
        EnumSet<Field> changed = EnumSet.noneOf(Field.class);
        for (Field f : Field.values()) {
            if (!Objects.equals(before.get(f), after.get(f))) {
                changed.add(f);
            }
        }
        return changed;
    }
}
