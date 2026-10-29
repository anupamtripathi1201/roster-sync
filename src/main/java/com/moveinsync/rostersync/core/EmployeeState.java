package com.moveinsync.rostersync.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/*
  Immutable snapshot of one employee as the sync engine sees it.
  hrVersion is the highest source-system version applied so far (the idempotency high-water mark).
 */
public final class EmployeeState {

    private final String employeeId;
    private final Map<Field, String> fields;
    private final long hrVersion;
    private final boolean active;

    public EmployeeState(String employeeId, Map<Field, String> fields, long hrVersion, boolean active) {
        this.employeeId = Objects.requireNonNull(employeeId, "employeeId");
        EnumMap<Field, String> copy = new EnumMap<>(Field.class);
        if (fields != null) {
            fields.forEach((k, v) -> {
                if (v != null && !v.isBlank()) {
                    copy.put(k, v.trim());
                }
            });
        }
        this.fields = Collections.unmodifiableMap(copy);
        this.hrVersion = hrVersion;
        this.active = active;
    }

    public String employeeId() { return employeeId; }
    public Map<Field, String> fields() { return fields; }
    public String get(Field f) { return fields.get(f); }
    public long hrVersion() { return hrVersion; }
    public boolean active() { return active; }

    /** Hash of the HR-owned fields only; this is what the nightly diff compares. */
    public long hrHash() {
        return hashHrFields(fields);
    }

    /** SHA-256 truncated to 64 bits over the HR fields in a fixed order. */
    public static long hashHrFields(Map<Field, String> fields) {
        StringBuilder canonical = new StringBuilder(128);
        for (Field f : Field.ownedBy(Field.Owner.HR)) {
            String v = fields.get(f);
            canonical.append(f.ordinal()).append('=').append(v == null ? "" : v.trim()).append('\u001F');
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(d, 0, 8).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Which diff bucket an employee belongs to. Stored on the employee row. */
    public static int bucketOf(String employeeId, int bucketCount) {
        return Math.floorMod(employeeId.hashCode(), bucketCount);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EmployeeState s && hrVersion == s.hrVersion && active == s.active
                && employeeId.equals(s.employeeId) && fields.equals(s.fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(employeeId, fields, hrVersion, active);
    }

    /** Never prints personal data. */
    @Override
    public String toString() {
        return "EmployeeState{id=" + employeeId + ", v=" + hrVersion + ", active=" + active + "}";
    }
}
