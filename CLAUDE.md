# Paylance backend — instructions de session

Paylance est un **middleware de paiement** (Wave, Orange Money) multi-tenant. Il manipule de
l'argent réel et des identifiants marchands. **La sécurité passe avant la vitesse de livraison
et avant le confort du front.**

## Contrat avec le front

- Le contrat d'API attendu par le front est dans
  **[../paylance-front/docs/contrat-api-backend.md](../paylance-front/docs/contrat-api-backend.md)**.
  Le lire avant toute modification d'une route, d'un DTO ou d'un format d'erreur.
- Sa déclinaison côté backend, avec les exigences de sécurité route par route et les écarts
  connus, est dans **[docs/contrat-api-securite.md](docs/contrat-api-securite.md)**. En cas de
  conflit entre les deux, **la règle de sécurité du backend l'emporte** : on adapte le contrat,
  on prévient le front, on ne baisse pas le niveau de sécurité.
- Le front (y compris son mode démo) décrit le comportement attendu, **pas** les contrôles de
  sécurité. Une validation faite côté front n'existe pas pour le backend.

## Règles non négociables

1. **Le backend est la seule source de vérité.** Toute validation (format, bornes, droits,
   statut, solde, appartenance à l'organisation) est refaite côté serveur, même si le front la
   fait déjà. Ne jamais faire confiance à un champ calculé ou affiché par le client (montant
   restant, rôle, statut, `emailVerified`, organisation « courante »).
2. **Isolation des tenants.** Toute donnée métier est lue et écrite dans le tenant courant
   (`TenantContext`, `TenantOwned`, `TenantGuardListener`). Un identifiant d'une autre
   organisation répond `404`, jamais `403` ni la donnée. `X-Organization-Id` n'est qu'une
   *sélection* : l'appartenance est vérifiée en base (`OrganizationContextFilter`).
3. **Autorisation explicite, refus par défaut.** Chaque nouvelle route est déclarée dans
   `SecurityConfig` avec le rôle minimal ; la chaîne se termine par `denyAll()`. Les actions qui
   déplacent de l'argent (remboursements, remboursements programmés, versements) et la gestion
   des comptes prestataires, clés API et membres sont réservées à `OWNER` / `ADMIN`.
   `VIEWER` est en lecture seule. Vérifier l'ordre des `requestMatchers` : le premier qui
   correspond gagne.
4. **Argent.** Montants en `long` centimes, jamais de `double`. Bornes min/max appliquées côté
   serveur. Calcul du solde remboursable **sous verrou** (`SELECT … FOR UPDATE` sur le
   paiement) en réservant les remboursements `PENDING` : deux requêtes simultanées ne doivent
   jamais dépasser le montant payé. Chaque changement de statut passe par
   `PaymentStatusTransitionService` (historique + log).
5. **Secrets.** Identifiants prestataires et secrets de webhook chiffrés (`EncryptionService`),
   jamais renvoyés en clair (masque `••••` + 4 derniers caractères), jamais journalisés.
   Mots de passe, jetons Keycloak, codes de vérification et `client_secret` n'apparaissent
   **jamais** dans les logs, les `PaymentLog`, les messages d'erreur ni les réponses (sauf le
   jeton d'accès dans le corps de `login`/`refresh`).
6. **Authentification.** JWT Keycloak validé (signature, `iss`, `exp`, audience). Le jeton de
   renouvellement ne circule que dans le cookie `paylance_rt` (`HttpOnly; Secure;
   SameSite=Strict; Path=/api/v1/public/auth`). Réponse `401` identique pour e-mail inconnu et
   mauvais mot de passe. Limitation de débit sur toutes les routes publiques.
7. **Erreurs.** Corps `ErrorResponse`, `timestamp` en `Instant` (UTC, avec `Z`), `message` en
   français pour les 4xx. Aucun détail interne (stack trace, SQL, réponse brute d'un opérateur,
   nom de classe) dans un message ; les 5xx renvoient un message générique.
8. **Dates.** `Instant` dans les réponses. Les paramètres `from`/`to` sans fuseau sont lus en
   UTC. Ne jamais dépendre du fuseau de la JVM.
9. **Webhooks.** Signature vérifiée avant tout traitement, en temps constant ; idempotence par
   identifiant d'événement ; le statut final est confirmé par l'opérateur, jamais déduit d'un
   paramètre de redirection côté client.
10. **Audit.** Toute action sensible (remboursement, désactivation de compte, changement de
    rôle, accès super admin) est tracée avec l'auteur (`sub` Keycloak) et la corrélation.

## Avant de considérer une tâche terminée

- Tests d'autorisation pour chaque nouvelle route : sans jeton (`401`), mauvais rôle (`403`),
  autre organisation (`404`), e-mail non vérifié quand c'est exigé (`403`).
- Test d'isolation tenant si la route lit des données métier (voir `TenantIsolationIT`).
- Migration Flyway **nouvelle** (`V2__…`, `V3__…`) : ne jamais modifier une migration déjà
  appliquée.
- `make test` puis `make verify` (Testcontainers, nécessite Docker).

## Repères

- Lancer : `make db` puis `make run` (port `8082`). Voir [README.md](README.md).
- Chaînes de sécurité : [SecurityConfig.java](src/main/java/com/paylance/security/SecurityConfig.java)
  (1 : clé API serveur à serveur, 2 : webhooks, 3 : utilisateurs JWT).
- Erreurs : [GlobalExceptionHandler.java](src/main/java/com/paylance/web/exception/GlobalExceptionHandler.java).
