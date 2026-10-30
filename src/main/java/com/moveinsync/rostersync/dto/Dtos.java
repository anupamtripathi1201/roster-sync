package com.moveinsync.rostersync.dto;

import com.moveinsync.rostersync.core.ChangeApplier;
import com.moveinsync.rostersync.core.EmployeeState;
import com.moveinsync.rostersync.core.Field;
import com.moveinsync.rostersync.core.RecordValidator.Violation;
import com.moveinsync.rostersync.core.RosterEvent;
import com.moveinsync.rostersync.domain.ReconciliationRun;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class Dtos {

    private Dtos() {
    }


    public record ApiError(String code, String message, String traceId, List<Violation> fieldErrors) {
    }

    public record EventRequest(String eventType, String employeeId, Long version, Instant sourceTimestamp,
                               String name, String email, String phone, String managerId, String officeCode,
                               String shiftCode, String pickupPoint, String specialAssistance) {

        public RosterEvent.Type type() {
            try {
                return eventType == null ? null : RosterEvent.Type.valueOf(eventType.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        public Map<Field, String> fields() {
            Map<Field, String> m = new EnumMap<>(Field.class);
            m.put(Field.NAME, name);
            m.put(Field.EMAIL, email);
            m.put(Field.PHONE, phone);
            m.put(Field.MANAGER_ID, managerId);
            m.put(Field.OFFICE_CODE, officeCode);
            m.put(Field.SHIFT_CODE, shiftCode);
            m.put(Field.PICKUP_POINT, pickupPoint);
            m.put(Field.SPECIAL_ASSISTANCE, specialAssistance);
            m.values().removeIf(v -> v == null || v.isBlank());
            return m;
        }

        public RosterEvent toEvent() {
            return RosterEvent.of(type(), employeeId.trim(), version, sourceTimestamp, fields());
        }

        @Override
        public String toString() {
            return "EventRequest{type=" + eventType + ", employeeId=" + employeeId + ", version=" + version + "}";
        }
    }

    public record EventBatch(@NotNull @Size(min = 1, max = 1000) List<EventRequest> events) {
    }

    public record BatchResult(int received, int applied, int duplicates, int stale, int rejected,
                              List<RecordError> errors, List<OwnershipNote> ownershipIgnored) {

        public record RecordError(int index, String employeeId, List<Violation> violations) {
        }

        public record OwnershipNote(int index, String employeeId, List<String> ignoredFields) {
        }

        public static final class Builder {
            private final int received;
            private int applied, duplicates, stale;
            private final List<RecordError> errors = new ArrayList<>();
            private final List<OwnershipNote> ownership = new ArrayList<>();

            public Builder(int received) {
                this.received = received;
            }

            public void count(ChangeApplier.Decision d) {
                switch (d) {
                    case APPLIED -> applied++;
                    case DUPLICATE -> duplicates++;
                    case STALE -> stale++;
                    case VERSION_CONFLICT -> { /* reported via reject() */ }
                }
            }

            public void reject(int index, String employeeId, List<Violation> violations) {
                errors.add(new RecordError(index, shortId(employeeId), violations));
            }

            public void ownershipIgnored(int index, String employeeId, List<String> fields) {
                ownership.add(new OwnershipNote(index, shortId(employeeId), fields));
            }

            public BatchResult build() {
                return new BatchResult(received, applied, duplicates, stale, errors.size(), errors, ownership);
            }

            private static String shortId(String id) {
                return id == null || id.length() <= 64 ? id : id.substring(0, 64);
            }
        }
    }

    public record EmployeeView(String employeeId, String name, String email, String phone, String managerId,
                               String officeCode, String shiftCode, String pickupPoint, String specialAssistance,
                               boolean active, long hrVersion) {

        public static EmployeeView of(EmployeeState s) {
            return new EmployeeView(s.employeeId(), s.get(Field.NAME), s.get(Field.EMAIL), s.get(Field.PHONE),
                    s.get(Field.MANAGER_ID), s.get(Field.OFFICE_CODE), s.get(Field.SHIFT_CODE),
                    s.get(Field.PICKUP_POINT), s.get(Field.SPECIAL_ASSISTANCE), s.active(), s.hrVersion());
        }

        @Override
        public String toString() {
            return "EmployeeView{employeeId=" + employeeId + "}";
        }
    }

    public record TransportUpdate(@Size(max = 255) String pickupPoint, @Size(max = 500) String specialAssistance) {
        @Override
        public String toString() {
            return "TransportUpdate{}";
        }
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
        @Override
        public String toString() {
            return "LoginRequest{username=" + username + "}";
        }
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    }


    public record WhoAmI(String username, String role, String tenantId, String employeeId) {
    }


    public record RunView(Long runId, ReconciliationRun.Status status, Instant startedAt, Instant finishedAt,
                          long fileRows, long rejectedRows, long added, long changed, long unchanged, long stale,
                          long activeBefore, long missingFromFile, double missingRatio, double brakeThreshold,
                          long deactivated, String decidedBy, String failureCode) {

        public static RunView of(ReconciliationRun r) {
            return new RunView(r.getId(), r.getStatus(), r.getStartedAt(), r.getFinishedAt(), r.getFileRows(),
                    r.getRejectedRows(), r.getAdded(), r.getChanged(), r.getUnchanged(), r.getStale(),
                    r.getStoreActive(), r.getMissing(), r.getMissingRatio(), r.getThreshold(), r.getDeactivated(),
                    r.getDecidedBy(), r.getFailureCode());
        }
    }

    public record Rejection(long lineNumber, String employeeId, String codes) {
    }
}
