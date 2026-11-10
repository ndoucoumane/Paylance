package com.paylance.repository;

import com.paylance.domain.entity.Payment;
import com.paylance.domain.enums.Platform;
import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, String>, JpaSpecificationExecutor<Payment> {

    Optional<Payment> findByPlatformAndIdempotencyKey(Platform platform, String idempotencyKey);

    Optional<Payment> findByProviderTransactionId(String providerTransactionId);

    Optional<Payment> findByPaymentNumber(String paymentNumber);

    List<Payment> findByStatusInAndCreatedAtBefore(List<PaymentStatus> statuses, LocalDateTime threshold);

    List<Payment> findByStatusAndCallbackSuccessFalseAndCallbackAttemptsLessThan(PaymentStatus status, Integer maxAttempts);

    @Query("""
            SELECT p.status AS status, COUNT(p) AS count, COALESCE(SUM(p.amount), 0) AS totalAmount
            FROM Payment p
            WHERE (:from IS NULL OR p.createdAt >= :from) AND (:to IS NULL OR p.createdAt <= :to)
            GROUP BY p.status
            """)
    List<StatusAggregate> aggregateByStatus(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("""
            SELECT p.provider AS provider, COUNT(p) AS count, COALESCE(SUM(p.amount), 0) AS totalAmount
            FROM Payment p
            WHERE (:from IS NULL OR p.createdAt >= :from) AND (:to IS NULL OR p.createdAt <= :to)
            GROUP BY p.provider
            """)
    List<ProviderAggregate> aggregateByProvider(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("""
            SELECT p.platform AS platform, COUNT(p) AS count, COALESCE(SUM(p.amount), 0) AS totalAmount
            FROM Payment p
            WHERE (:from IS NULL OR p.createdAt >= :from) AND (:to IS NULL OR p.createdAt <= :to)
            GROUP BY p.platform
            """)
    List<PlatformAggregate> aggregateByPlatform(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    interface StatusAggregate {
        PaymentStatus getStatus();
        long getCount();
        long getTotalAmount();
    }

    interface ProviderAggregate {
        PaymentProvider getProvider();
        long getCount();
        long getTotalAmount();
    }

    interface PlatformAggregate {
        Platform getPlatform();
        long getCount();
        long getTotalAmount();
    }
}
