package com.moveinsync.rostersync.domain;

import com.moveinsync.rostersync.core.EmployeeState;
import com.moveinsync.rostersync.core.Field;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

@Entity
@Table(name = "employee")
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;
    @Column(name = "employee_id", nullable = false, updatable = false)
    private String employeeId;

    private String name;
    private String email;
    private String phone;
    @Column(name = "manager_id")
    private String managerId;
    @Column(name = "office_code")
    private String officeCode;
    @Column(name = "shift_code")
    private String shiftCode;
    @Column(name = "pickup_point")
    private String pickupPoint;
    @Column(name = "special_assistance")
    private String specialAssistance;

    @Column(name = "hr_version", nullable = false)
    private long hrVersion;
    @Column(name = "hr_hash", nullable = false)
    private long hrHash;
    @Column(nullable = false)
    private int bucket;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "deactivation_source")
    private String deactivationSource;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected Employee() {
    }

    public static Employee newFor(String tenantId, String employeeId, Instant now) {
        Employee e = new Employee();
        e.tenantId = tenantId;
        e.employeeId = employeeId;
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    public EmployeeState toState() {
        Map<Field, String> f = new EnumMap<>(Field.class);
        f.put(Field.NAME, name);
        f.put(Field.EMAIL, email);
        f.put(Field.PHONE, phone);
        f.put(Field.MANAGER_ID, managerId);
        f.put(Field.OFFICE_CODE, officeCode);
        f.put(Field.SHIFT_CODE, shiftCode);
        f.put(Field.PICKUP_POINT, pickupPoint);
        f.put(Field.SPECIAL_ASSISTANCE, specialAssistance);
        return new EmployeeState(employeeId, f, hrVersion, active);   // nulls are dropped by EmployeeState
    }


    public void applyState(EmployeeState s, int buckets, String deactivationSource, Instant now) {
        name = s.get(Field.NAME);
        email = s.get(Field.EMAIL);
        phone = s.get(Field.PHONE);
        managerId = s.get(Field.MANAGER_ID);
        officeCode = s.get(Field.OFFICE_CODE);
        shiftCode = s.get(Field.SHIFT_CODE);
        pickupPoint = s.get(Field.PICKUP_POINT);
        specialAssistance = s.get(Field.SPECIAL_ASSISTANCE);
        hrVersion = s.hrVersion();
        hrHash = s.hrHash();
        bucket = EmployeeState.bucketOf(employeeId, buckets);
        active = s.active();
        if (active) {
            this.deactivationSource = null;
        } else if (deactivationSource != null) {
            this.deactivationSource = deactivationSource;
        }
        updatedAt = now;
    }

    public String getEmployeeId() { return employeeId; }
    public long getHrVersion() { return hrVersion; }
    public boolean isActive() { return active; }
    public String getOfficeCode() { return officeCode; }
    public String getShiftCode() { return shiftCode; }
    public String getPickupPoint() { return pickupPoint; }
    public String getDeactivationSource() { return deactivationSource; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "Employee{employeeId=" + employeeId + ", v=" + hrVersion + ", active=" + active + "}";
    }
}
