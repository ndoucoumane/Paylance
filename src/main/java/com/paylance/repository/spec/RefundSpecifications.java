package com.paylance.repository.spec;

import com.paylance.domain.entity.Payment;
import com.paylance.domain.entity.Refund;
import com.paylance.domain.enums.RefundStatus;
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
                String pattern = SearchPatterns.containsPattern(query);
                Predicate qPredicate = cb.or(
                        SearchPatterns.matches(cb, root.get("id"), pattern),
                        SearchPatterns.matches(cb, root.get("reason"), pattern),
                        SearchPatterns.matches(cb, root.get("providerRefundId"), pattern),
                        SearchPatterns.matches(cb, root.get("requestedBy"), pattern),
                        SearchPatterns.matches(cb, paymentJoin.get("id"), pattern),
                        SearchPatterns.matches(cb, paymentJoin.get("paymentNumber"), pattern),
                        SearchPatterns.matches(cb, paymentJoin.get("payerPhone"), pattern),
                        SearchPatterns.matches(cb, paymentJoin.get("payerName"), pattern)
                );
                predicates.add(qPredicate);
            }

            // 2. Filtres ciblés
            if (paymentNumber != null && !paymentNumber.isBlank()) {
                predicates.add(SearchPatterns.matches(cb, paymentJoin.get("paymentNumber"), SearchPatterns.containsPattern(paymentNumber)));
            }

            if (phone != null && !phone.isBlank()) {
                predicates.add(SearchPatterns.matches(cb, paymentJoin.get("payerPhone"), SearchPatterns.containsPattern(phone)));
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
