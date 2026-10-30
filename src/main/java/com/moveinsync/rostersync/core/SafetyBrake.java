package com.moveinsync.rostersync.core;

/**
 * Stops a reconciliation that would deactivate an implausible share of employees.
 * A truncated or broken export looks exactly like "everyone left", so we never trust it blindly.
 */
public final class SafetyBrake {

    public record Verdict(boolean tripped, long missing, long active, double ratio, double threshold) {
    }

    private final double threshold;

    // threshold e.g. 0.30: trip when 30% or more of active employees are missing from the file
    public SafetyBrake(double threshold) {
        if (threshold <= 0 || threshold > 1) {
            throw new IllegalArgumentException("threshold must be in (0, 1]");
        }
        this.threshold = threshold;
    }

    public Verdict evaluate(long active, long missing) {
        if (active <= 0) {
            return new Verdict(false, missing, active, 0, threshold);
        }
        double ratio = (double) missing / active;
        return new Verdict(ratio >= threshold, missing, active, ratio, threshold);
    }
}
