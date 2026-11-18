package com.paylance.tenant;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Organisation (tenant) au nom de laquelle le thread courant travaille. Alimenté par
 * l'authentification (clé API ou JWT + X-Organization-Id) et lu par Hibernate via
 * {@link TenantIdentifierResolver} pour filtrer toutes les entités {@code @TenantId}.
 *
 * <p>Trois états :
 * <ul>
 *   <li>tenant défini : seules les lignes de cette organisation sont visibles et écrites ;</li>
 *   <li>aucun tenant : rien n'est visible (identifiant sentinelle qui ne correspond à aucune ligne) ;</li>
 *   <li>mode système : lecture transverse, réservé aux jobs planifiés, à la résolution d'un
 *   webhook et à l'authentification. Chaque élément trouvé est ensuite traité dans son tenant.</li>
 * </ul>
 *
 * <p>Hibernate fige le tenant à l'ouverture de la session : on ne change jamais de tenant au
 * milieu d'une transaction (vérifié).
 */
public final class TenantContext {

    /** Identifiant « racine » Hibernate : désactive le filtre de tenant. */
    public static final String SYSTEM_TENANT = "__paylance_system__";
    /** Identifiant utilisé quand aucun tenant n'est défini : ne correspond à aucune organisation. */
    public static final String NO_TENANT = "__paylance_no_tenant__";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<String> currentOrganizationId() {
        String value = CURRENT.get();
        return value == null || SYSTEM_TENANT.equals(value) ? Optional.empty() : Optional.of(value);
    }

    public static String requireOrganizationId() {
        return currentOrganizationId()
                .orElseThrow(() -> new IllegalStateException("Aucune organisation courante pour cette opération"));
    }

    public static boolean isSystem() {
        return SYSTEM_TENANT.equals(CURRENT.get());
    }

    /** Identifiant transmis à Hibernate. */
    static String hibernateTenantId() {
        String value = CURRENT.get();
        return value != null ? value : NO_TENANT;
    }

    public static void runAs(String organizationId, Runnable action) {
        callAs(organizationId, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T callAs(String organizationId, Supplier<T> action) {
        if (organizationId == null || organizationId.isBlank()) {
            throw new IllegalArgumentException("organizationId requis");
        }
        return switchTo(organizationId, action);
    }

    public static void runAsSystem(Runnable action) {
        callAsSystem(() -> {
            action.run();
            return null;
        });
    }

    public static <T> T callAsSystem(Supplier<T> action) {
        return switchTo(SYSTEM_TENANT, action);
    }

    private static <T> T switchTo(String tenant, Supplier<T> action) {
        String previous = CURRENT.get();
        if (!tenant.equals(previous) && TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Changement de tenant interdit à l'intérieur d'une transaction");
        }
        CURRENT.set(tenant);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** Pour les filtres HTTP, qui encadrent eux-mêmes toute la requête (set puis clear en finally). */
    public static void set(String organizationId) {
        CURRENT.set(organizationId);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Valeur brute, pour la propagation vers un thread asynchrone. */
    public static String snapshot() {
        return CURRENT.get();
    }
}
