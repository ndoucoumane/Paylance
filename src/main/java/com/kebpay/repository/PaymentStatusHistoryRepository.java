package com.kebpay.repository;

import com.kebpay.domain.entity.PaymentStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentStatusHistoryRepository extends JpaRepository<PaymentStatusHistory, String> {

    List<PaymentStatusHistory> findByPaymentIdOrderByCreatedAtAsc(String paymentId);
}
