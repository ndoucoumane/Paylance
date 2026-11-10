package com.paylance.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Réservation atomique des tâches planifiées arrivées à échéance, sûre entre plusieurs instances :
 * une seule requête {@code UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING}
 * fait passer chaque ligne à son statut « en cours » ; deux instances ne réservent jamais la
 * même ligne. SQL direct (hors Hibernate) : lecture transverse à tous les tenants, chaque ligne
 * est ensuite traitée dans le tenant renvoyé.
 */
@Component
@RequiredArgsConstructor
public class DueWorkClaimer {

    public record ClaimedItem(String id, String organizationId) {
    }

    private final JdbcTemplate jdbcTemplate;

    public List<ClaimedItem> claimScheduledPayments(LocalDateTime now, int limit) {
        return jdbcTemplate.query("""
                UPDATE scheduled_payments SET status = 'EXECUTING', updated_at = ?
                WHERE id IN (
                    SELECT id FROM scheduled_payments
                    WHERE status = 'SCHEDULED' AND scheduled_at <= ?
                    ORDER BY scheduled_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED)
                RETURNING id, organization_id
                """,
                (rs, i) -> new ClaimedItem(rs.getString("id"), rs.getString("organization_id")),
                Timestamp.valueOf(now), Timestamp.valueOf(now), limit);
    }
}
