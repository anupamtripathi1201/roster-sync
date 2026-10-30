package com.moveinsync.rostersync.repository;

import com.moveinsync.rostersync.domain.Employee;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByTenantIdAndEmployeeId(String tenantId, String employeeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Employee e where e.tenantId = :tenantId and e.employeeId = :employeeId")
    Optional<Employee> lock(@Param("tenantId") String tenantId, @Param("employeeId") String employeeId);

    long countByTenantIdAndActiveTrue(String tenantId);
}
