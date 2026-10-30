package com.moveinsync.rostersync.config;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class FailureInjector {

    public enum Point {
        // Employee row written, transaction not yet committed.
        AFTER_EMPLOYEE_WRITE,
        // Relay handing the event to the broker.
        PUBLISH,
        // Broker accepted the event, outbox row not yet marked published.
        AFTER_PUBLISH_BEFORE_MARK
    }

    public static class SimulatedCrash extends RuntimeException {
        public SimulatedCrash(Point p) {
            super("simulated crash at " + p);
        }
    }

    private final Map<Point, AtomicInteger> armed = new ConcurrentHashMap<>();

    public void arm(Point point, int times) {
        armed.put(point, new AtomicInteger(times));
    }

    public void disarmAll() {
        armed.clear();
    }

    public void maybeFail(Point point) {
        AtomicInteger left = armed.get(point);
        if (left != null && left.getAndDecrement() > 0) {
            throw new SimulatedCrash(point);
        }
    }
}
