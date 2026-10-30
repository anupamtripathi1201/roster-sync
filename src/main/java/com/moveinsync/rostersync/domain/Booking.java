package com.moveinsync.rostersync.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

// A seat on a cab trip (cab_trip table). Cancelling is idempotent.
@Entity
@Table(name = "booking")
public class Booking {

    public enum Status { CONFIRMED, CANCELLED, COMPLETED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;
    @Column(name = "employee_id", nullable = false)
    private String employeeId;
    @Column(name = "trip_id", nullable = false)
    private Long tripId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;
    @Column(name = "cancelled_reason")
    private String cancelledReason;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Booking() {
    }

    //return true only if this call changed the booking, so a redelivered event changes nothing
    public boolean cancel(String reason, Instant now) {
        if (status != Status.CONFIRMED) {
            return false;
        }
        status = Status.CANCELLED;
        cancelledReason = reason;
        updatedAt = now;
        return true;
    }

    public Long getTripId() { return tripId; }
    public Status getStatus() { return status; }
}
