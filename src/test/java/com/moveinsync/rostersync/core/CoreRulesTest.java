package com.moveinsync.rostersync.core;

import com.moveinsync.rostersync.core.ChangeApplier.Decision;
import com.moveinsync.rostersync.core.ChangeApplier.Result;
import com.moveinsync.rostersync.core.RecordValidator.Input;
import com.moveinsync.rostersync.core.RecordValidator.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Version gate, ordering, idempotency, field ownership and validation: no Spring, no database. */
class CoreRulesTest {

    private final ChangeApplier applier = new ChangeApplier();
    private final RecordValidator validator = new RecordValidator();
    private final Instant now = Instant.parse("2026-10-01T10:00:00Z");

    static Map<Field, String> hr(String id, String office, String shift) {
        Map<Field, String> m = new EnumMap<>(Field.class);
        m.put(Field.NAME, "Person " + id);
        m.put(Field.EMAIL, id.toLowerCase() + "@acme.example");
        m.put(Field.PHONE, "+919876543210");
        m.put(Field.OFFICE_CODE, office);
        m.put(Field.SHIFT_CODE, shift);
        return m;
    }

    private EmployeeState created(String id, long v) {
        return applier.apply(Optional.empty(), new RosterEvent.Create(id, v, now, hr(id, "BLR1", "DAY"))).newState();
    }

    // ---------- version gate ----------

    @Test
    void versionGateCoversEveryCase() {
        assertThat(ChangeApplier.decide(null, 0, false)).isEqualTo(Decision.APPLIED);
        assertThat(ChangeApplier.decide(6L, 7, false)).isEqualTo(Decision.APPLIED);
        assertThat(ChangeApplier.decide(7L, 6, true)).isEqualTo(Decision.STALE);
        assertThat(ChangeApplier.decide(7L, 7, true)).isEqualTo(Decision.DUPLICATE);
        assertThat(ChangeApplier.decide(7L, 7, false)).isEqualTo(Decision.VERSION_CONFLICT);
        assertThat(ChangeApplier.decide(Long.MAX_VALUE - 1, Long.MAX_VALUE, false)).isEqualTo(Decision.APPLIED);
    }

    /** The example from the case study. */
    @Test
    void transferV7BeforeShiftChangeV6EndsWithV7OfficeAndShift() {
        EmployeeState v5 = created("E1", 5);
        Result v7 = applier.apply(Optional.of(v5), new RosterEvent.Update("E1", 7, now, hr("E1", "HYD1", "NIGHT")));
        Result v6 = applier.apply(Optional.of(v7.newState()), new RosterEvent.Update("E1", 6, now, hr("E1", "BLR1", "EVENING")));

        assertThat(v7.decision()).isEqualTo(Decision.APPLIED);
        assertThat(v6.decision()).isEqualTo(Decision.STALE);
        assertThat(v6.newState().get(Field.OFFICE_CODE)).isEqualTo("HYD1");
        assertThat(v6.newState().get(Field.SHIFT_CODE)).isEqualTo("NIGHT");
        assertThat(v6.newState().hrVersion()).isEqualTo(7);
    }

    @Test
    void replayingTheSameFeedTwiceChangesNothing() {
        List<RosterEvent> feed = List.of(
                new RosterEvent.Create("E1", 1, now, hr("E1", "BLR1", "DAY")),
                new RosterEvent.Update("E1", 3, now, hr("E1", "HYD1", "DAY")),
                new RosterEvent.Update("E1", 2, now, hr("E1", "BLR1", "NIGHT")),
                new RosterEvent.Deactivate("E1", 4, now));
        List<Decision> second = new ArrayList<>();
        EmployeeState first = replay(Optional.empty(), feed, new ArrayList<>());
        assertThat(replay(Optional.of(first), feed, second)).isEqualTo(first);
        assertThat(second).doesNotContain(Decision.APPLIED);
    }

    private EmployeeState replay(Optional<EmployeeState> start, List<RosterEvent> feed, List<Decision> out) {
        Optional<EmployeeState> s = start;
        for (RosterEvent e : feed) {
            Result r = applier.apply(s, e);
            out.add(r.decision());
            s = Optional.ofNullable(r.newState());
        }
        return s.orElseThrow();
    }

    @Test
    void sameVersionDifferentContentIsConflictAndNotApplied() {
        Result r = applier.apply(Optional.of(created("E1", 1)), new RosterEvent.Update("E1", 1, now, hr("E1", "HYD1", "DAY")));
        assertThat(r.decision()).isEqualTo(Decision.VERSION_CONFLICT);
        assertThat(r.newState().get(Field.OFFICE_CODE)).isEqualTo("BLR1");
    }

    @Test
    void updateBeforeCreateAppliesAndLateCreateIsStale() {
        Result upd = applier.apply(Optional.empty(), new RosterEvent.Update("E1", 2, now, hr("E1", "HYD1", "DAY")));
        Result late = applier.apply(Optional.of(upd.newState()), new RosterEvent.Create("E1", 1, now, hr("E1", "BLR1", "DAY")));
        assertThat(upd.applied()).isTrue();
        assertThat(late.decision()).isEqualTo(Decision.STALE);
    }

    @Test
    void deactivateOfUnknownEmployeeLeavesTombstoneThatBlocksLateCreate() {
        Result tomb = applier.apply(Optional.empty(), new RosterEvent.Deactivate("E9", 5, now));
        Result late = applier.apply(Optional.of(tomb.newState()), new RosterEvent.Create("E9", 4, now, hr("E9", "BLR1", "DAY")));
        assertThat(late.decision()).isEqualTo(Decision.STALE);
        assertThat(late.newState().active()).isFalse();
    }

    @Test
    void deactivateFlagsTheTransition() {
        assertThat(applier.apply(Optional.of(created("E1", 1)), new RosterEvent.Deactivate("E1", 2, now)).deactivated()).isTrue();
    }

    // ---------- field ownership ----------

    @Test
    void hrUpdateNeverOverwritesTransportFields() {
        EmployeeState withPickup = applier.applyTransportEdit(created("E1", 1),
                Map.of(Field.PICKUP_POINT, "Gate 4", Field.SPECIAL_ASSISTANCE, "wheelchair")).newState();
        Map<Field, String> payload = hr("E1", "HYD1", "DAY");
        payload.put(Field.PICKUP_POINT, "HR says main road");

        Result r = applier.apply(Optional.of(withPickup), new RosterEvent.Update("E1", 2, now, payload));

        assertThat(r.applied()).isTrue();
        assertThat(r.newState().get(Field.OFFICE_CODE)).isEqualTo("HYD1");
        assertThat(r.newState().get(Field.PICKUP_POINT)).isEqualTo("Gate 4");
        assertThat(r.newState().get(Field.SPECIAL_ASSISTANCE)).isEqualTo("wheelchair");
        assertThat(r.droppedFields()).containsExactly(Field.PICKUP_POINT);
    }

    @Test
    void transportEditCannotTouchHrFieldsOrVersion() {
        Result r = applier.applyTransportEdit(created("E1", 1), Map.of(Field.NAME, "Changed", Field.PICKUP_POINT, "Gate 1"));
        assertThat(r.newState().get(Field.NAME)).isEqualTo("Person E1");
        assertThat(r.newState().hrVersion()).isEqualTo(1);
        assertThat(r.droppedFields()).containsExactly(Field.NAME);
        assertThat(r.changedFields()).containsExactly(Field.PICKUP_POINT);
    }

    // ---------- validation ----------

    private List<Violation> validate(RosterEvent.Type type, String id, Long v, Map<Field, String> f) {
        return validator.validate(new Input(id, type, v, f), Set.of("BLR1", "HYD1"));
    }

    @Test
    void validRecordPasses() {
        assertThat(validate(RosterEvent.Type.CREATE, "E1", 1L, hr("E1", "BLR1", "DAY"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "phone", "+0123456789", "98765-43210", "+9198765432101234"})
    void malformedPhoneIsRejected(String phone) {
        Map<Field, String> f = hr("E1", "BLR1", "DAY");
        f.put(Field.PHONE, phone);
        assertThat(validate(RosterEvent.Type.UPDATE, "E1", 1L, f)).containsExactly(new Violation("PHONE", "INVALID_FORMAT"));
    }

    @Test
    void unknownOfficeIsRejected() {
        assertThat(validate(RosterEvent.Type.UPDATE, "E1", 1L, hr("E1", "XXX9", "DAY")))
                .containsExactly(new Violation("OFFICE_CODE", "UNKNOWN_OFFICE"));
    }

    @Test
    void violationsNeverContainTheRejectedValue() {
        Map<Field, String> f = hr("E1", "XXX9", "DAY");
        f.put(Field.EMAIL, "secret.person@leak");
        f.put(Field.PHONE, "+91-SECRET");
        List<Violation> v = validate(RosterEvent.Type.UPDATE, "E1", 1L, f);
        assertThat(v).hasSize(3).allSatisfy(x -> assertThat(x.message()).doesNotContainIgnoringCase("secret"));
    }

    @Test
    void brokenRecordReportsEveryProblem() {
        assertThat(validate(null, null, null, Map.of())).extracting(Violation::field)
                .contains("employeeId", "type", "version");
        assertThat(validate(RosterEvent.Type.CREATE, "E1", 1L, Map.of(Field.OFFICE_CODE, "BLR1")))
                .extracting(Violation::code).contains("REQUIRED");
    }

    @Test
    void deactivateNeedsNoFieldsAndSelfManagerIsRejected() {
        assertThat(validate(RosterEvent.Type.DEACTIVATE, "E1", 2L, Map.of())).isEmpty();
        Map<Field, String> f = hr("E1", "BLR1", "DAY");
        f.put(Field.MANAGER_ID, "E1");
        assertThat(validate(RosterEvent.Type.UPDATE, "E1", 1L, f)).containsExactly(new Violation("MANAGER_ID", "SELF_MANAGER"));
    }
}
