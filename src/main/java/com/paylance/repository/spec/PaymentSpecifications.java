package com.paylance.repository.spec;

import com.paylance.domain.entity.Payment;
import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.PaymentStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public final class PaymentSpecifications {

    private PaymentSpecifications() {
    }

    public static Specification<Payment> filter(
            String query,
            String paymentNumber,
            String phone,
            String applicationId,
            PaymentStatus status,
            PaymentProvider provider,
            Long minAmount,
            Long maxAmount,
            LocalDateTime from,
            LocalDateTime to
    ) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 1. Recherche globale 'query' / 'q'
            if (query != null && !query.isBlank()) {
                String pattern = "%" + query.trim().toLowerCase() + "%";
                Predicate qPredicate = cb.or(
                        cb.like(cb.lower(root.get("id")), pattern),
                        cb.like(cb.lower(root.get("paymentNumber")), pattern),
                        cb.like(cb.lower(root.get("payerPhone")), pattern),
                        cb.like(cb.lower(root.get("payerName")), pattern),
                        cb.like(cb.lower(root.get("externalReference")), pattern),
                        cb.like(cb.lower(root.get("providerTransactionId")), pattern),
                        cb.like(cb.lower(root.get("idempotencyKey")), pattern)
                );
                predicates.add(qPredicate);
            }

            // 2. Filtres ciblés
            if (paymentNumber != null && !paymentNumber.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("paymentNumber")), "%" + paymentNumber.trim().toLowerCase() + "%"));
            }

            if (phone != null && !phone.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("payerPhone")), "%" + phone.trim().toLowerCase() + "%"));
            }

            if (applicationId != null && !applicationId.isBlank()) {
                predicates.add(cb.equal(root.get("applicationId"), applicationId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (provider != null) {
                predicates.add(cb.equal(root.get("provider"), provider));
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
