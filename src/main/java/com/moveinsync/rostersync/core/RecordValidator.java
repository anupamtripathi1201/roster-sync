package com.moveinsync.rostersync.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Boundary validation. Each rule is a small class; add a rule by adding a class to RULES.
 * Violations carry the field NAME and a code, never the value, so they are safe to log and return.
 */
public final class RecordValidator {

    public record Violation(String field, String code) {
        public static Violation record(String code) {
            return new Violation(null, code);
        }

        public String message() {
            return field == null ? code : field + ": " + code;
        }
    }

    /** What is being validated. type/version may be null when the input is broken. */
    public record Input(String employeeId, RosterEvent.Type type, Long version, Map<Field, String> fields) {
        String get(Field f) {
            return fields == null ? null : fields.get(f);
        }

        boolean isUpsert() {
            return type == RosterEvent.Type.CREATE || type == RosterEvent.Type.UPDATE;
        }
    }

    interface Rule {
        void check(Input in, Set<String> officeCodes, List<Violation> out);
    }

    //Template for "if the field is present it must look like X". Subclasses say which field and what is valid.
    abstract static class FormatRule implements Rule {
        abstract Field field();

        abstract boolean valid(String value, Set<String> officeCodes);

        String code() {
            return "INVALID_FORMAT";
        }

        @Override
        public final void check(Input in, Set<String> officeCodes, List<Violation> out) {
            String v = in.get(field());
            if (v != null && !v.isBlank() && !valid(v.trim(), officeCodes)) {
                out.add(new Violation(field().name(), code()));
            }
        }
    }

    static final class PhoneRule extends FormatRule {
        private static final Pattern P = Pattern.compile("^\\+?[1-9]\\d{9,14}$");   // E.164-style
        Field field() { return Field.PHONE; }
        boolean valid(String v, Set<String> o) { return P.matcher(v).matches(); }
    }

    static final class EmailRule extends FormatRule {
        private static final Pattern P = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
        Field field() { return Field.EMAIL; }
        boolean valid(String v, Set<String> o) { return v.length() <= 254 && P.matcher(v).matches(); }
    }

    static final class OfficeRule extends FormatRule {
        Field field() { return Field.OFFICE_CODE; }
        boolean valid(String v, Set<String> offices) { return offices != null && offices.contains(v); }
        @Override String code() { return "UNKNOWN_OFFICE"; }
    }

    // Employee id, version and manager id must be well-formed; nobody manages themselves.
    static final class IdentifierRule implements Rule {
        @Override
        public void check(Input in, Set<String> o, List<Violation> out) {
            if (in.employeeId() == null || !ID.matcher(in.employeeId()).matches()) {
                out.add(new Violation("employeeId", "INVALID_ID"));
            }
            if (in.type() == null) {
                out.add(new Violation("type", "UNKNOWN_TYPE"));
            }
            if (in.version() == null || in.version() < 0) {
                out.add(new Violation("version", "INVALID_VERSION"));
            }
            String m = in.get(Field.MANAGER_ID);
            if (m != null && !m.isBlank()) {
                if (!ID.matcher(m.trim()).matches()) {
                    out.add(new Violation(Field.MANAGER_ID.name(), "INVALID_ID"));
                } else if (m.trim().equals(in.employeeId())) {
                    out.add(new Violation(Field.MANAGER_ID.name(), "SELF_MANAGER"));
                }
            }
        }
    }

    // Creates/updates are full HR state, so the core HR fields must be there.
    static final class RequiredRule implements Rule {
        @Override
        public void check(Input in, Set<String> o, List<Violation> out) {
            if (!in.isUpsert()) {
                return;
            }
            for (Field f : List.of(Field.NAME, Field.EMAIL, Field.OFFICE_CODE)) {
                String v = in.get(f);
                if (v == null || v.isBlank()) {
                    out.add(new Violation(f.name(), "REQUIRED"));
                }
            }
        }
    }

    public static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final List<Rule> RULES = List.of(
            new IdentifierRule(), new RequiredRule(), new EmailRule(), new PhoneRule(), new OfficeRule());

    // Runs every rule and returns all violations at once (empty list = valid).
    public List<Violation> validate(Input input, Set<String> officeCodes) {
        List<Violation> out = new ArrayList<>();
        for (Rule r : RULES) {
            r.check(input, officeCodes, out);
        }
        return out;
    }
}
