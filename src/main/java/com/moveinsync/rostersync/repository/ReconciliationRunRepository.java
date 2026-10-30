package com.moveinsync.rostersync.repository;

import com.moveinsync.rostersync.domain.ReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, Long> {

    Optional<ReconciliationRun> findByIdAndTenantId(Long id, String tenantId);

    boolean existsByTenantIdAndStatus(String tenantId, ReconciliationRun.Status status);

    List<ReconciliationRun> findByStatus(ReconciliationRun.Status status);
}
