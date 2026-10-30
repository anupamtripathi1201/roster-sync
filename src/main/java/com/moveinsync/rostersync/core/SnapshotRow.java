package com.moveinsync.rostersync.core;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;


public record SnapshotRow(long lineNumber, String employeeId, long version, Map<Field, String> fields,
                          boolean presenceOnly) {

    public static final String HEADER = "employee_id,version,name,email,phone,manager_id,office_code,shift_code";
    private static final Field[] COLUMNS = {
            Field.NAME, Field.EMAIL, Field.PHONE, Field.MANAGER_ID, Field.OFFICE_CODE, Field.SHIFT_CODE};

    public static SnapshotRow presence(long lineNumber, String employeeId) {
        return new SnapshotRow(lineNumber, employeeId, -1, Map.of(), true);
    }

    public long hrHash() {
        return EmployeeState.hashHrFields(fields);
    }

    // Outcome of parsing one line: a row, or the reasons it is unreadable.
    public sealed interface Parsed permits Ok, Bad {
    }

    public record Ok(SnapshotRow row) implements Parsed {
    }

    // employeeId is null when even that could not be read.
    public record Bad(long lineNumber, String employeeId, List<RecordValidator.Violation> violations) implements Parsed {
    }

    // Assumes values contain no commas (documented in the README). A cut-off last line becomes bad.
    public static Parsed parse(long lineNumber, String line) {
        String[] p = line.split(",", -1);
        String id = p.length > 0 && !p[0].isBlank() ? p[0].trim() : null;
        if (p.length != 2 + COLUMNS.length) {
            return new Bad(lineNumber, id, List.of(RecordValidator.Violation.record("MALFORMED_ROW")));
        }
        long version;
        try {
            version = Long.parseLong(p[1].trim());
        } catch (NumberFormatException e) {
            return new Bad(lineNumber, id, List.of(new RecordValidator.Violation("version", "INVALID_VERSION")));
        }
        Map<Field, String> fields = new EnumMap<>(Field.class);
        for (int i = 0; i < COLUMNS.length; i++) {
            String v = p[i + 2].trim();
            if (!v.isEmpty()) {
                fields.put(COLUMNS[i], v);
            }
        }
        return new Ok(new SnapshotRow(lineNumber, id, version, fields, false));
    }

    public static boolean isHeader(String line) {
        return line != null && line.trim().equalsIgnoreCase(HEADER);
    }
}
