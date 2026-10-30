package com.moveinsync.rostersync.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.moveinsync.rostersync.core.ChangeApplier;
import com.moveinsync.rostersync.core.RecordValidator;
import com.moveinsync.rostersync.core.SafetyBrake;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;


@Configuration
@EnableCaching
public class AppConfig {

    public static final String OFFICES = "offices";
    public static final String EMPLOYEES = "employees";

    @Bean
    public ChangeApplier changeApplier() {
        return new ChangeApplier();
    }

    @Bean
    public RecordValidator recordValidator() {
        return new RecordValidator();
    }

    @Bean
    public SafetyBrake safetyBrake(AppProperties props) {
        return new SafetyBrake(props.brakeThreshold());
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public CacheManager cacheManager(AppProperties props) {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                new CaffeineCache(OFFICES, Caffeine.newBuilder().expireAfterWrite(props.officeCacheTtl())
                        .maximumSize(10_000).recordStats().build()),
                new CaffeineCache(EMPLOYEES, Caffeine.newBuilder().expireAfterWrite(props.employeeCacheTtl())
                        .maximumSize(50_000).recordStats().build())));
        return manager;
    }
}
