package com.paylance.repository;

import com.paylance.domain.entity.ScheduledPayment;
import com.paylance.domain.enums.ScheduledPaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ScheduledPaymentRepository extends JpaRepository<ScheduledPayment, String>, JpaSpecificationExecutor<ScheduledPayment> {

    @Query(value = """
            SELECT * FROM scheduled_payments
            WHERE status = 'SCHEDULED' AND scheduled_at <= :threshold
            ORDER BY scheduled_at
            """, nativeQuery = true)
    List<ScheduledPayment> findDueForClaim(@Param("threshold") LocalDateTime threshold);

    Page<ScheduledPayment> findByStatus(ScheduledPaymentStatus status, Pageable pageable);
}
