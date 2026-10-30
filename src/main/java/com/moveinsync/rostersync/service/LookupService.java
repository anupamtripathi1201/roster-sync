package com.moveinsync.rostersync.service;

import com.moveinsync.rostersync.config.AppConfig;
import com.moveinsync.rostersync.dto.Dtos.EmployeeView;
import com.moveinsync.rostersync.exception.ApiException;
import com.moveinsync.rostersync.repository.EmployeeRepository;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;

/**
 * Cached reads. What can be stale and for how long:
 *  - office codes: a new office can be rejected as UNKNOWN_OFFICE on another instance for up to 5 min;
 *  - employee view: up to 30 s on another instance (this instance evicts right after commit).
 * The write path never reads from these caches, so staleness cannot affect ordering or idempotency.
 * Cache keys include the tenant, so a cached entry can never be served to another tenant.
 */

@Service
public class LookupService {

    private final JdbcTemplate jdbc;
    private final EmployeeRepository employees;
    private final CacheManager caches;

    public LookupService(JdbcTemplate jdbc, EmployeeRepository employees, CacheManager caches) {
        this.jdbc = jdbc;
        this.employees = employees;
        this.caches = caches;
    }

    @Cacheable(cacheNames = AppConfig.OFFICES, key = "#tenantId")
    public Set<String> officeCodes(String tenantId) {
        return Set.copyOf(jdbc.queryForList("select code from office where tenant_id = ?", String.class, tenantId));
    }

    @CacheEvict(cacheNames = AppConfig.OFFICES, key = "#tenantId")
    public void addOffice(String tenantId, String code, String name) {
        jdbc.update("insert into office (tenant_id, code, name) values (?, ?, ?)", tenantId, code, name);
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = AppConfig.EMPLOYEES, key = "#tenantId + ':' + #employeeId")
    public EmployeeView employee(String tenantId, String employeeId) {
        return employees.findByTenantIdAndEmployeeId(tenantId, employeeId)
                .map(e -> EmployeeView.of(e.toState()))
                // same answer whether it does not exist or belongs to another tenant
                .orElseThrow(() -> ApiException.notFound("EMPLOYEE_NOT_FOUND", "Employee not found"));
    }

    // Evict AFTER commit: evicting before would let a reader re-cache the old row in between.
    public void evictEmployeeAfterCommit(String tenantId, String employeeId) {
        Runnable evict = () -> {
            Cache c = caches.getCache(AppConfig.EMPLOYEES);
            if (c != null) {
                c.evict(tenantId + ":" + employeeId);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict.run();
                }
            });
        } else {
            evict.run();
        }
    }
}
