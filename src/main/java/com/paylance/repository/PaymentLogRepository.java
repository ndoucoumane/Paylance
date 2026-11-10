package com.paylance.repository;

import com.paylance.domain.entity.PaymentLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentLogRepository extends JpaRepository<PaymentLog, String> {

    List<PaymentLog> findByPaymentIdOrderByCreatedAtAsc(String paymentId);

    List<PaymentLog> findByCorrelationIdOrderByCreatedAtAsc(String correlationId);
}
