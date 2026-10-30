package com.moveinsync.rostersync.messaging;

import com.moveinsync.rostersync.core.ChangeApplier;

import java.time.Instant;
import java.util.List;

/**
 * What downstream consumers receive: which employee, which fields changed (names, never values),
 * no personal data on the bus. eventId is unique per change; consumers deduplicate on it,
 * because delivery is at-least-once.
 */
public record ChangeEvent(String eventId, Type type, String tenantId, String employeeId, long hrVersion,
                          List<String> changedFields, String source, Instant occurredAt) {

    public enum Type {
        EMPLOYEE_CREATED,
        EMPLOYEE_UPDATED,
        EMPLOYEE_DEACTIVATED,
        EMPLOYEE_REACTIVATED,
        EMPLOYEE_TOMBSTONED,
        TRANSPORT_PROFILE_UPDATED;

        public static Type forHrChange(ChangeApplier.Result r, boolean wasActive) {
            if (r.created()) {
                return r.newState().active() ? EMPLOYEE_CREATED : EMPLOYEE_TOMBSTONED;
            }
            if (r.deactivated()) {
                return EMPLOYEE_DEACTIVATED;
            }
            return !wasActive && r.newState().active() ? EMPLOYEE_REACTIVATED : EMPLOYEE_UPDATED;
        }
    }
}
