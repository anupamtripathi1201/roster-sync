package com.moveinsync.rostersync.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

// A change event waiting to be (or already) published. Written in the same transaction as the change.
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;
    @Column(name = "employee_id", nullable = false, updatable = false)
    private String employeeId;
    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;
    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "published_at")
    private Instant publishedAt;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;
    @Column(name = "last_error")
    private String lastError;

    protected OutboxEvent() {
    }

    public OutboxEvent(String eventId, String tenantId, String employeeId, String eventType, String payload, Instant now) {
        this.eventId = eventId;
        this.tenantId = tenantId;
        this.employeeId = employeeId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = now;
        this.nextAttemptAt = now;
    }

    public void markPublished(Instant now) {
        publishedAt = now;
        lastError = null;
    }

    // Exponential backoff 1 s, 2 s, 4 s ... capped at 60 s. Stores the exception TYPE only.
    public void markFailed(Instant now, String errorType) {
        attempts++;
        long delay = Math.min(60, 1L << Math.min(attempts - 1, 6));
        nextAttemptAt = now.plusSeconds(delay);
        lastError = errorType;
    }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public int getAttempts() { return attempts; }
}
