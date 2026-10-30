package com.moveinsync.rostersync.service;

import com.moveinsync.rostersync.domain.Booking;
import com.moveinsync.rostersync.messaging.ChangeEvent;
import com.moveinsync.rostersync.messaging.EventPublisher;
import com.moveinsync.rostersync.observability.SyncMetrics;
import com.moveinsync.rostersync.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class BookingService implements EventPublisher.Subscriber {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookings;
    private final SyncMetrics metrics;
    private final Clock clock;

    public BookingService(BookingRepository bookings, SyncMetrics metrics, Clock clock) {
        this.bookings = bookings;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public boolean supports(ChangeEvent.Type type) {
        return type == ChangeEvent.Type.EMPLOYEE_DEACTIVATED;
    }

    @Override
    @Transactional
    public void handle(ChangeEvent event) {
        Instant now = clock.instant();
        int cancelled = 0;
        for (Booking b : bookings.findFutureConfirmed(event.tenantId(), event.employeeId(), now)) {
            if (b.cancel("EMPLOYEE_DEACTIVATED", now)) {
                bookings.saveAndFlush(b);
                bookings.freeSeat(b.getTripId());
                cancelled++;
            }
        }
        if (cancelled > 0) {
            metrics.bookingsCancelled(cancelled);
            log.info("future bookings cancelled employeeId={} count={}", event.employeeId(), cancelled);
        }
    }
}
