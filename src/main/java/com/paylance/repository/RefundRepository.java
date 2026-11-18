package com.paylance.repository;

import com.paylance.domain.entity.Refund;
import com.paylance.domain.enums.RefundStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;

import java.util.List;

public interface RefundRepository extends JpaRepository<Refund, String>, JpaSpecificationExecutor<Refund> {

    @EntityGraph(attributePaths = {"payment"})
    List<Refund> findByPaymentIdOrderByCreatedAtDesc(String paymentId);

    /** Somme des remboursements d'un paiement dans les statuts donnés (0 s'il n'y en a aucun). */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM Refund r WHERE r.payment.id = :paymentId AND r.status IN :statuses")
    long sumAmountByPaymentIdAndStatusIn(@Param("paymentId") String paymentId,
                                         @Param("statuses") Collection<RefundStatus> statuses);

    /** Sommes par paiement, en une requête (liste du dashboard). Paiements sans remboursement absents. */
    @Query("""
            SELECT r.payment.id AS paymentId, COALESCE(SUM(r.amount), 0) AS total
            FROM Refund r
            WHERE r.payment.id IN :paymentIds AND r.status IN :statuses
            GROUP BY r.payment.id
            """)
    List<PaymentRefundTotal> sumAmountByPaymentIds(@Param("paymentIds") Collection<String> paymentIds,
                                                   @Param("statuses") Collection<RefundStatus> statuses);

    interface PaymentRefundTotal {
        String getPaymentId();
        long getTotal();
    }

    boolean existsByPaymentIdAndStatus(String paymentId, RefundStatus status);

    @EntityGraph(attributePaths = {"payment"})
    Page<Refund> findByStatus(RefundStatus status, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"payment"})
    Page<Refund> findAll(Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"payment"})
    Page<Refund> findAll(Specification<Refund> spec, Pageable pageable);
}
