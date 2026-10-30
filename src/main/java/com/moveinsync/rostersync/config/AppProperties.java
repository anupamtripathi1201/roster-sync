package com.moveinsync.rostersync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// Every tunable in one typed object, bound from the "rostersync" section of application.properties.
@ConfigurationProperties(prefix = "rostersync")
public record AppProperties(String jwtSecret,
                            Duration tokenTtl,
                            double brakeThreshold,
                            int diffBuckets,
                            boolean relayEnabled,
                            long relayPollMs,
                            Duration employeeCacheTtl,
                            Duration officeCacheTtl,
                            boolean demoData,
                            boolean failureInjection) {
}
