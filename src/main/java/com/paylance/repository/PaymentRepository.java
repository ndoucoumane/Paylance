package com.paylance.repository;

import com.paylance.domain.entity.Payment;
import com.paylance.domain.enums.PaymentProvider;
import com.paylance.domain.enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Toutes les requêtes dérivées/JPQL sont restreintes au tenant courant par Hibernate
 * ({@code @TenantId}). {@code findById} ne l'est pas : il est couvert par TenantGuardListener.
 */
public interface PaymentRepository extends JpaRepository<Payment, String>, JpaSpecificationExecutor<Payment> {

    /** {@code applicationId} peut être {@code null} (paiement sans application) : Spring Data génère alors IS NULL. */
    Optional<Payment> findByApplicationIdAndIdempotencyKey(String applicationId, String idempotencyKey);

    Optional<Payment> findByProviderAccountIdAndProviderTransactionId(String providerAccountId, String providerTransactionId);

    Optional<Payment> findByPaymentNumber(String paymentNumber);

    /**
     * Charge un paiement avec un verrou de ligne jusqu'à la fin de la transaction, pour
     * sérialiser les opérations sur le solde remboursable : deux remboursements simultanés du
     * même paiement s'exécutent l'un après l'autre, le second voit la réservation du premier.
     *
     * <p>{@code FOR NO KEY UPDATE} et non {@code FOR UPDATE} : les écritures de journal faites en
     * {@code REQUIRES_NEW} (payment_logs, clé étrangère vers payments) prennent un verrou
     * {@code FOR KEY SHARE} sur ce paiement ; {@code FOR UPDATE} les bloquerait jusqu'à la fin de
     * la transaction qui les attend, soit un interblocage que PostgreSQL ne détecte pas.
     *
     * <p>Requête native, donc <b>non filtrée par {@code @TenantId}</b> : l'organisation est passée
     * explicitement. Toujours appeler avec {@code TenantContext.requireOrganizationId()}.
     */
    @Query(value = "SELECT * FROM payments WHERE id = :id AND organization_id = :organizationId FOR NO KEY UPDATE",
            nativeQuery = true)
    Optional<Payment> findByIdForUpdate(@Param("id") String id, @Param("organizationId") String organizationId);

    /** Réconciliation (mode système) : paiements de tous les tenants restés en attente. */
    List<Payment> findByStatusInAndCreatedAtBefore(List<PaymentStatus> statuses, LocalDateTime threshold);

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
            SELECT p.applicationId AS applicationId, COUNT(p) AS count, COALESCE(SUM(p.amount), 0) AS totalAmount
            FROM Payment p
            WHERE (:from IS NULL OR p.createdAt >= :from) AND (:to IS NULL OR p.createdAt <= :to)
            GROUP BY p.applicationId
            """)
    List<ApplicationAggregate> aggregateByApplication(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

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

    interface ApplicationAggregate {
        String getApplicationId();
        long getCount();
        long getTotalAmount();
    }
}
