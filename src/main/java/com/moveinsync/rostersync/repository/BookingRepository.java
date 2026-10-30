package com.moveinsync.rostersync.repository;

import com.moveinsync.rostersync.domain.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    @Query(value = """
            select b.* from booking b join cab_trip t on t.id = b.trip_id
            where b.tenant_id = :tenantId and b.employee_id = :employeeId
              and b.status = 'CONFIRMED' and t.departure_at > :now
            """, nativeQuery = true)
    List<Booking> findFutureConfirmed(@Param("tenantId") String tenantId, @Param("employeeId") String employeeId,
                                      @Param("now") Instant now);


    @Modifying
    @Query(value = "update cab_trip set seats_booked = seats_booked - 1 where id = :tripId and seats_booked > 0",
            nativeQuery = true)
    int freeSeat(@Param("tripId") Long tripId);

    List<Booking> findByTenantIdAndEmployeeId(String tenantId, String employeeId);
}
