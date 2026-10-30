package com.moveinsync.rostersync.repository;

import com.moveinsync.rostersync.domain.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    @Query("select o from OutboxEvent o where o.publishedAt is null and o.nextAttemptAt <= :now order by o.id")
    List<OutboxEvent> findDue(@Param("now") Instant now, Pageable page);

    long countByPublishedAtIsNull();

    Optional<OutboxEvent> findFirstByPublishedAtIsNullOrderByIdAsc();

    List<OutboxEvent> findByTenantIdAndEmployeeIdOrderByIdAsc(String tenantId, String employeeId);

    long countByTenantId(String tenantId);
}
