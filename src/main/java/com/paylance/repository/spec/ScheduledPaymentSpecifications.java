package com.paylance.repository.spec;

import com.paylance.domain.entity.ScheduledPayment;
import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.ScheduledPaymentStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class ScheduledPaymentSpecifications {

    private ScheduledPaymentSpecifications() {
    }

    public static Specification<ScheduledPayment> filter(
            String query,
            String phone,
            ScheduledPaymentStatus status,
            PaymentProvider provider,
            String providerAccountId,
            Long minAmount,
            Long maxAmount,
            LocalDateTime from,
            LocalDateTime to,
            Collection<String> matchedPaymentIds
    ) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 1. Recherche globale 'query' / 'q'
            if (query != null && !query.isBlank()) {
                String pattern = SearchPatterns.containsPattern(query);
                List<Predicate> qOrPredicates = new ArrayList<>();
                qOrPredicates.add(SearchPatterns.matches(cb, root.get("id"), pattern));
                qOrPredicates.add(SearchPatterns.matches(cb, root.get("payerPhone"), pattern));
                qOrPredicates.add(SearchPatterns.matches(cb, root.get("externalReference"), pattern));
                qOrPredicates.add(SearchPatterns.matches(cb, root.get("note"), pattern));
                qOrPredicates.add(SearchPatterns.matches(cb, root.get("paymentId"), pattern));

                if (matchedPaymentIds != null && !matchedPaymentIds.isEmpty()) {
                    qOrPredicates.add(root.get("paymentId").in(matchedPaymentIds));
                }

                predicates.add(cb.or(qOrPredicates.toArray(new Predicate[0])));
            } else if (matchedPaymentIds != null && !matchedPaymentIds.isEmpty()) {
                predicates.add(root.get("paymentId").in(matchedPaymentIds));
            }

            // 2. Filtres ciblés
            if (phone != null && !phone.isBlank()) {
                predicates.add(SearchPatterns.matches(cb, root.get("payerPhone"), SearchPatterns.containsPattern(phone)));
            }

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            if (provider != null) {
                predicates.add(cb.equal(root.get("provider"), provider));
            }

            if (providerAccountId != null && !providerAccountId.isBlank()) {
                predicates.add(cb.equal(root.get("providerAccountId"), providerAccountId));
            }

            if (minAmount != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("amount"), minAmount));
            }

            if (maxAmount != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("amount"), maxAmount));
            }

            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("scheduledAt"), from));
            }

            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("scheduledAt"), to));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
