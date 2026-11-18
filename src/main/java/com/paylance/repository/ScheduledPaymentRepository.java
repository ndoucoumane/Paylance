package com.paylance.repository;

import com.paylance.domain.entity.ScheduledPayment;
import com.paylance.domain.enums.ScheduledPaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ScheduledPaymentRepository extends JpaRepository<ScheduledPayment, String>, JpaSpecificationExecutor<ScheduledPayment> {

    Page<ScheduledPayment> findByStatus(ScheduledPaymentStatus status, Pageable pageable);
}
