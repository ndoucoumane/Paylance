package com.kebpay.repository;

import com.kebpay.domain.entity.Refund;
import com.kebpay.domain.enums.RefundStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface RefundRepository extends JpaRepository<Refund, String>, JpaSpecificationExecutor<Refund> {

    @EntityGraph(attributePaths = {"payment"})
    List<Refund> findByPaymentIdOrderByCreatedAtDesc(String paymentId);

    @EntityGraph(attributePaths = {"payment"})
    Page<Refund> findByStatus(RefundStatus status, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"payment"})
    Page<Refund> findAll(Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"payment"})
    Page<Refund> findAll(Specification<Refund> spec, Pageable pageable);
}
