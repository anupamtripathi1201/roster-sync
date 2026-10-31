package com.moveinsync.rostersync.config;

import com.moveinsync.rostersync.core.Field;
import com.moveinsync.rostersync.core.RosterEvent;
import com.moveinsync.rostersync.security.CurrentUser.Role;
import com.moveinsync.rostersync.security.UserAccountService;
import com.moveinsync.rostersync.service.EmployeeChangeService;
import com.moveinsync.rostersync.service.LookupService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/**
 * Demo data for docker compose: tenants acme and globex, one user per role (password "demo123"),
 * five employees and some cab bookings. Runs only with DEMO_DATA=true and only on an empty database.
 */
@Component
@ConditionalOnProperty(name = "rostersync.demo-data", havingValue = "true")
public class DemoDataSeeder implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final UserAccountService users;
    private final LookupService lookup;
    private final EmployeeChangeService changes;
    private final Clock clock;

    public DemoDataSeeder(JdbcTemplate jdbc, UserAccountService users, LookupService lookup, EmployeeChangeService changes,
                          Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.lookup = lookup;
        this.changes = changes;
        this.clock = clock;
    }

    @Override
    public void run(String... args) {
        Long existing = jdbc.queryForObject("select count(*) from app_user", Long.class);
        if (existing != null && existing > 0) {
            return;
        }
        users.createUser("hr-acme", "demo123", Role.HR_SYSTEM, "acme", null);
        users.createUser("admin-acme", "demo123", Role.ADMIN, "acme", null);
        users.createUser("ops-acme", "demo123", Role.OPERATOR, "acme", null);
        users.createUser("emp-e1001", "demo123", Role.EMPLOYEE, "acme", "E1001");
        users.createUser("admin-globex", "demo123", Role.ADMIN, "globex", null);

        lookup.addOffice("acme", "BLR1", "Bengaluru Whitefield");
        lookup.addOffice("acme", "HYD1", "Hyderabad Hitec City");
        lookup.addOffice("globex", "PUN1", "Pune Hinjewadi");

        Instant now = clock.instant();
        for (int i = 1; i <= 5; i++) {
            Map<Field, String> f = new EnumMap<>(Field.class);
            f.put(Field.NAME, "Demo Employee " + i);
            f.put(Field.EMAIL, "employee" + i + "@acme.example");
            f.put(Field.PHONE, "+91987654320" + i);
            f.put(Field.OFFICE_CODE, "BLR1");
            f.put(Field.SHIFT_CODE, "DAY");
            changes.applyHrEvent("acme", new RosterEvent.Create("E100" + i, 1, now, f), "DEMO_SEED");
        }

        // E1001 and E1002 each have a seat on tomorrow's cab and on yesterday's (already done) cab
        long tomorrow = trip(now.plus(Duration.ofDays(1)));
        long yesterday = trip(now.minus(Duration.ofDays(1)));
        for (String emp : new String[]{"E1001", "E1002"}) {
            for (long trip : new long[]{tomorrow, yesterday}) {
                jdbc.update("insert into booking (tenant_id, employee_id, trip_id, status, updated_at) values (?, ?, ?, 'CONFIRMED', ?)",
                        "acme", emp, trip, Timestamp.from(now));
                jdbc.update("update cab_trip set seats_booked = seats_booked + 1 where id = ?", trip);
            }
        }
    }

    private long trip(Instant departure) {
        jdbc.update("insert into cab_trip (tenant_id, office_code, departure_at, capacity, seats_booked) values (?, ?, ?, 4, 0)",
                "acme", "BLR1", Timestamp.from(departure));
        return jdbc.queryForObject("select max(id) from cab_trip", Long.class);
    }
}
