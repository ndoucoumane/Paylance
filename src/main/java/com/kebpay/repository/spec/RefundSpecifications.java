package com.kebpay.repository.spec;

import com.kebpay.domain.entity.Payment;
import com.kebpay.domain.entity.Refund;
import com.kebpay.domain.enums.RefundStatus;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public final class RefundSpecifications {

    private RefundSpecifications() {
    }

    public static Specification<Refund> filter(
            String query,
            String paymentNumber,
            String phone,
            RefundStatus status,
            Long minAmount,
            Long maxAmount,
            LocalDateTime from,
            LocalDateTime to
    ) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            Join<Refund, Payment> paymentJoin = root.join("payment", JoinType.LEFT);

            // 1. Recherche globale 'query' / 'q' (multi-attributs)
            if (query != null && !query.isBlank()) {
                String pattern = "%" + query.trim().toLowerCase() + "%";
                Predicate qPredicate = cb.or(
                        cb.like(cb.lower(root.get("id")), pattern),
                        cb.like(cb.lower(root.get("reason")), pattern),
                        cb.like(cb.lower(root.get("providerRefundId")), pattern),
                        cb.like(cb.lower(root.get("requestedBy")), pattern),
                        cb.like(cb.lower(paymentJoin.get("id")), pattern),
                        cb.like(cb.lower(paymentJoin.get("paymentNumber")), pattern),
                        cb.like(cb.lower(paymentJoin.get("payerPhone")), pattern),
                        cb.like(cb.lower(paymentJoin.get("payerName")), pattern)
                );
                predicates.add(qPredicate);
            }

            // 2. Filtres ciblés
            if (paymentNumber != null && !paymentNumber.isBlank()) {
                predicates.add(cb.like(cb.lower(paymentJoin.get("paymentNumber")), "%" + paymentNumber.trim().toLowerCase() + "%"));
            }

            if (phone != null && !phone.isBlank()) {
                predicates.add(cb.like(cb.lower(paymentJoin.get("payerPhone")), "%" + phone.trim().toLowerCase() + "%"));
            }

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            if (minAmount != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("amount"), minAmount));
            }

            if (maxAmount != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("amount"), maxAmount));
            }

            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }

            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
