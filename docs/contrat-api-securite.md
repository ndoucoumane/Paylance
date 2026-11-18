# Contrat d'API — déclinaison backend et exigences de sécurité

Ce document traduit le contrat du front
([../paylance-front/docs/contrat-api-backend.md](../../paylance-front/docs/contrat-api-backend.md),
appelé ici « le contrat ») en exigences d'implémentation pour le backend. Les numéros entre
parenthèses (§2.3…) renvoient aux sections du contrat.

**Principe directeur.** Le backend est la seule source de vérité. Le front, et son mode démo,
décrivent l'expérience attendue (formats, messages, parcours). Ils ne garantissent aucune
règle : chaque validation, chaque droit et chaque calcul d'argent est refait ici. Quand une
exigence de sécurité contredit le contrat, la sécurité l'emporte et l'écart est listé en
section 5 pour que le front s'adapte.

Les règles générales (isolation des tenants, secrets, argent, erreurs) sont dans
[CLAUDE.md](../CLAUDE.md). Ce document ne les répète que lorsqu'une route les applique de
manière particulière.

---

## 1. Écarts constatés dans le code actuel

État au 18/11/2026, branche `feat/paylance-multitenant`. À corriger avant de brancher le front
réel. Classés par gravité.

### 1.1 Critiques (argent ou droits)

> **Corrigés le 18/11/2026** : C1 à C5, et I1 pour les routes `organizations` et
> `provider-accounts`. Tests : `RefundSafetyIT`, `AuthorizationIT`. Décisions prises
> (validées le 18/11/2026) :
>
> - **C1** : verrou `SELECT … FOR NO KEY UPDATE` (pas `FOR UPDATE`, qui bloquerait les
>   écritures de `payment_logs` en `REQUIRES_NEW`), réservation des `PENDING`, index unique
>   partiel `uq_refunds_one_pending_per_payment` (migration V2). Si l'opérateur ne répond pas
>   de façon exploitable, le remboursement **reste `PENDING`** et bloque le paiement : c'est à
>   l'exploitation de le régulariser (log `[REFUND_OUTCOME_UNKNOWN]`). Aucune réconciliation
>   automatique des remboursements n'existe encore.
> - **C4** : `POST /dashboard/payments` réservé à `OWNER`/`ADMIN`.
> - **C5** : contrôle fondé sur le claim `email_verified` du jeton Keycloak (signé, donc fiable).
>   Il passera sur la colonne en base quand la table `users` existera (§3.1). Le front doit
>   renouveler le jeton après la vérification de l'e-mail.
> - En plus : les rôles realm Keycloak passent par une **liste blanche**
>   (`PAYLANCE_SUPER_ADMIN` seulement). Un rôle realm `org_owner` donnait auparavant
>   `ROLE_ORG_OWNER` dans toutes les organisations de l'utilisateur.

| # | Constat | Fichier | Correction attendue |
|---|---|---|---|
| C1 | **Double remboursement possible.** Le solde restant est calculé sans verrou, puis l'appel à l'opérateur est fait. Deux requêtes simultanées (double clic, deux onglets, tâche planifiée + humain) lisent le même solde et remboursent toutes les deux. Les remboursements `PENDING` ne sont pas réservés. | [RefundService.java](../src/main/java/com/paylance/service/RefundService.java) | Charger le paiement avec `PESSIMISTIC_WRITE` dans une transaction, calculer `remaining = amount − Σ(SUCCESS + PENDING)`, créer le `Refund` `PENDING`, valider la transaction, **puis** appeler l'opérateur hors transaction. Un seul remboursement `PENDING` par paiement à la fois (`409` sinon). |
| C2 | **Remboursement partiel Wave non bloqué.** La règle « Wave = remboursement total uniquement » (§3.2, §4.6) n'existe que côté front. | RefundService | Si `provider = WAVE` et `amount` fourni ≠ solde restant : `422` « Wave ne permet que le remboursement total d'un paiement. ». |
| C3 | **Remboursements programmés accessibles aux `DEVELOPER`.** `/dashboard/payments/*/scheduled-refunds` et `/dashboard/scheduled-refunds/**` ne sont pas dans la liste `MANAGE` et tombent sur la règle générale `WRITE`. | [SecurityConfig.java](../src/main/java/com/paylance/security/SecurityConfig.java) | Ajouter ces chemins à la règle `MANAGE` **avant** l'implémentation des routes. |
| C4 | **Création de paiement par un `DEVELOPER` depuis le dashboard.** `POST /dashboard/payments` (§4.7) tomberait sur `WRITE`. | SecurityConfig | Décision produit à prendre ; par défaut `MANAGE` (OWNER/ADMIN). Voir 3.6. |
| C5 | **Aucune exigence d'e-mail vérifié.** Un compte non vérifié peut créer une organisation et utiliser le dashboard. | — | Contrôle serveur sur `email_verified` **en base** (pas le claim) pour `/organizations/**`, `/onboarding/**`, `/dashboard/**` : `403`. |

### 1.2 Importants (conformité au contrat)

> **Corrigés le 18/11/2026** : I2 (`timestamp` en `Instant`, `correlationId` partout), I3
> (`requested_by` = nom affichable, `requested_by_user_id` = `sub` pour l'audit, migration V3),
> I6 (CORS avec cookies, origines explicites, `*` refusé au démarrage), I7 (plafond 1000, tri en
> liste blanche), I8. I4 et I5 sont corrigés pour les remboursements, les organisations et les
> comptes prestataires ; les autres services utilisent encore des exceptions génériques.
> Aussi corrigés : recherche insensible aux espaces avec jokers échappés, messages `400` et
> `403` lisibles, `refundableAmount` calculé par le serveur, `null` explicites dans les
> réponses du front, `from`/`to` acceptant `Z`, fuseau UTC fixé hors de `main()`, UTF-8 sur
> les réponses d'erreur des filtres. Tests : `FrontContractIT`.

| # | Constat | Correction |
|---|---|---|
| I1 | Pas de contrôleur pour `/api/v1/organizations` ni `/api/v1/dashboard/provider-accounts`, alors que le contrat les marque ✅ et que `SecurityConfig` les protège. Les services et DTO existent (`OrganizationService`, `ProviderAccountService`, `web/dto/onboarding`). | Créer `OrganizationController` et `ProviderAccountController`. |
| I2 | `ErrorResponse.timestamp` est un `LocalDateTime` (sérialisé sans fuseau) ; idem dans `OrganizationContextFilter`. | Passer à `Instant`. Remplir `correlationId` partout. |
| I3 | `requestedBy` vaut `USER:<email>` (`CurrentActor.describe()`). Le contrat attend le nom affichable (§3.4). | Stocker **deux** champs : `requested_by_user_id` (`sub`, pour l'audit) et `requested_by_name` (affichage). Le nom vient de la base utilisateur, pas d'un champ envoyé par le client. |
| I4 | Montant de remboursement invalide → `400` (`IllegalArgumentException`) ; le contrat attend `422`. Statut non remboursable → message sans le libellé français du statut. | Exceptions métier dédiées (`BusinessRuleException` → `422`, `ConflictException` → `409`) au lieu des exceptions Java génériques. |
| I5 | `IllegalStateException` / `IllegalArgumentException` sont renvoyées telles quelles au client : n'importe quelle exception de bibliothèque peut faire fuiter un message interne. | Ne mapper en 4xx que des exceptions métier Paylance ; tout le reste → `500` générique, détail en log uniquement. |
| I6 | CORS : pas d'`allowCredentials`, nécessaire au cookie de renouvellement (§1.4). | `setAllowCredentials(true)` avec origines explicites, refuser `*` au démarrage. |
| I7 | `size` de pagination non plafonné explicitement (défaut Spring : 2000). | `spring.data.web.pageable.max-page-size: 1000` (§7.2). |
| I8 | `/api/v1/me`, `/api/v1/plans`, `/api/v1/onboarding/**`, `/api/v1/public/**` ne sont déclarés nulle part : ils tombent sur `denyAll()`. | Les déclarer explicitement (voir matrice §2). |

---

## 2. Matrice des droits

Rôles d'organisation : `OWNER`, `ADMIN`, `DEVELOPER`, `VIEWER`. « Auth » = jeton valide ;
« Vérifié » = `email_verified = true` en base.

| Route | Public | Auth | Vérifié | VIEWER | DEVELOPER | ADMIN | OWNER |
|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| `POST /public/register`, `/public/auth/*`, `/public/leads` | ✔ (limité en débit) | | | | | | |
| `GET /plans` | ✔ | | | | | | |
| `GET /me`, `/me/email-verification/**` | | ✔ | | | | | |
| `GET /organizations`, `PUT/GET /organizations/current/*` | | ✔ | ✔ | ✔ en lecture | ✔ en lecture | ✔ | ✔ |
| `POST /onboarding/providers/complete` | | ✔ | ✔ | | | ✔ | ✔ |
| `GET /dashboard/payments/**` (liste, stats, détail, historique, remboursements) | | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| `GET /dashboard/provider-accounts` (secrets masqués) | | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| `POST /dashboard/provider-accounts`, `/{id}/test`, `/{id}/disable` | | ✔ | ✔ | | | ✔ | ✔ |
| `POST /dashboard/payments` | | ✔ | ✔ | | (décision C4) | ✔ | ✔ |
| `POST /dashboard/payments/{id}/refunds` | | ✔ | ✔ | | | ✔ | ✔ |
| `GET /dashboard/scheduled-refunds` | | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| `POST /dashboard/payments/{id}/scheduled-refunds`, `/scheduled-refunds/{id}/cancel` | | ✔ | ✔ | | | ✔ | ✔ |

Les routes `PUT /organizations/current/*` (profil, préférences, formule) sont réservées à
`OWNER`/`ADMIN` dès que l'organisation existe. La première création (`PUT …/profile` sans
organisation) est ouverte à l'utilisateur vérifié, qui devient `OWNER`.

Une organisation `SUSPENDED` : lecture seule pour tous, toute écriture → `403`.

---

## 3. Exigences par domaine

### 3.1 Session — `GET /api/v1/me` (§2.2)

> **Version minimale en place (18/11/2026)**, `MeController` : `user` lu dans le jeton,
> `organization` lue en base (même ordre que `GET /organizations`, masquée tant que l'e-mail
> n'est pas vérifié), `progress` simplifié (`COMPANY` sans organisation, `DONE` sinon). Elle
> permet au front réel de fonctionner avec la connexion Keycloak actuelle. Les points
> ci-dessous restent la cible.

- Identité tirée **uniquement** du `sub` du jeton validé. Aucun paramètre d'identifiant.
- Créer une table `users` (clé = `sub` Keycloak) : prénom, nom, e-mail en minuscules,
  `email_verified`, `terms_accepted_at`, horodatages. Synchronisée à l'inscription et à la
  vérification, pas à partir des claims à chaque appel.
- `organization` = l'organisation dont l'utilisateur est membre, déterminée en base. Si
  plusieurs, la plus ancienne adhésion (ordre stable, cohérent avec « la première » du §1.3).
- `progress` calculé côté serveur à partir de l'état stocké (`onboarding_progress` par
  organisation). Le client ne peut pas déclarer une étape terminée autrement qu'en validant
  l'étape elle-même. Refuser une étape dont la précédente n'est pas terminée (`409`).

### 3.2 Inscription — `POST /api/v1/public/register` (§2.3)

- Validations serveur identiques au contrat (longueurs, regex mot de passe, `acceptTerms = true`),
  e-mail normalisé (`trim`, minuscules, NFC) **avant** le contrôle d'unicité.
- Création dans Keycloak via un compte de service dédié, rôle minimal `manage-users` sur le
  realm Paylance uniquement. Identifiants de ce compte en variables d'environnement, jamais en
  dépôt.
- Le mot de passe est transmis à Keycloak et **jamais** stocké, journalisé ni réémis.
- Limitation de débit : par IP (ex. 5 / heure) et globale. Prévoir un CAPTCHA si abus.
- `409` sur e-mail existant : accepté par le contrat, mais c'est une **fuite d'existence de
  compte**. Atténuation : limitation de débit stricte, message identique quel que soit l'état du
  compte existant (vérifié ou non, désactivé). Voir 5.2.
- Échec Keycloak après création partielle : compensation (suppression dans Keycloak) pour ne
  pas laisser de compte orphelin.

### 3.3 Connexion — `/api/v1/public/auth/{login,refresh,logout}` (§2.3 bis)

- Client Keycloak **confidentiel** dédié, `client_secret` en variable d'environnement. Direct
  Access Grants activé uniquement sur ce client.
- **Brute force** : la détection Keycloak ne suffit pas (elle permet de bloquer un compte
  ciblé, donc un déni de service). Ajouter côté backend une limitation par IP et par e-mail
  (ex. 10 essais / 15 min / IP, 5 / 15 min / e-mail), avec `429` et `Retry-After`.
- Réponse `401` **identique** (corps, en-têtes, et durée à peu près constante) pour e-mail
  inconnu, mot de passe faux et compte non vérifié côté Keycloak.
- Mapper les erreurs Keycloak sans les relayer : `invalid_grant` → `401`, compte désactivé →
  `403`, compte temporairement bloqué → `429`. Jamais le `error_description` brut.
- Cookie `paylance_rt` : `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/public/auth`,
  `Max-Age` = durée de session Keycloak. `Secure` obligatoire hors profil `dev`.
- **CSRF** sur `refresh` et `logout` (authentifiés par cookie) : `SameSite=Strict` plus
  vérification serveur de l'en-tête `Origin` contre la liste `dashboard-origins` ; refuser si
  absent ou inconnu (`403`).
- `refresh` : rotation systématique ; activer *Revoke Refresh Token* (réutilisation = 0) dans
  Keycloak pour qu'un jeton volé et rejoué invalide la session.
- `logout` : révocation côté Keycloak, puis cookie effacé (`Max-Age=0`), même si Keycloak
  échoue (réponse `204` dans tous les cas).
- Réponses avec `Cache-Control: no-store`.
- Jeton d'accès : durée courte (5 minutes), audience vérifiée par le resource server.

**Mot de passe oublié** (prévu plus tard) : `forgot-password` répond toujours `204`, même pour
un e-mail inconnu ; jeton à usage unique, haché en base, 30 minutes ; révoquer toutes les
sessions après réinitialisation.

### 3.4 Vérification de l'e-mail (§2.4)

- Code à 6 chiffres généré par `SecureRandom`, stocké **haché** (HMAC-SHA256 avec un secret
  serveur), jamais en clair.
- Comparaison en temps constant. Décrément de `attemptsLeft` atomique (`UPDATE … WHERE
  attempts_left > 0`), pour qu'une rafale parallèle ne contourne pas la limite de 5.
- Paramètres du contrat : 10 min de validité, 30 s entre deux envois, 5 tentatives. Ajouter un
  plafond d'envois par utilisateur et par jour (ex. 10) pour éviter l'usage du service comme
  relais d'e-mails.
- Un nouveau code invalide le précédent.
- Après succès : `email_verified = true` en base **et** dans Keycloak, dans cet ordre, avec
  reprise si l'appel Keycloak échoue.

### 3.5 Organisation, préférences, formule, prestataires (§2.5 à §2.8)

- Les routes `…/organizations/current/*` **n'acceptent pas** d'identifiant d'organisation venant
  du client : l'organisation est résolue en base à partir du `sub` (même règle qu'en 3.1).
- Toutes les valeurs de référence (pays, secteurs, tailles, devises, fuseaux, langues) sont
  validées contre des **listes fermées côté serveur** (enum ou table), pas contre ce que le
  front envoie.
- `website` : `https://` uniquement, longueur max 200, pas d'IP privée ni de `localhost`.
  Ne jamais faire de requête serveur vers cette URL.
- `slug` : généré serveur (`Slugs`), unicité garantie par contrainte en base.
- Formule : `selfServe = false` → `422`. La liste des formules est une donnée serveur ; le
  client ne peut pas envoyer de prix ni de fonctionnalités.
- Comptes prestataires :
  - identifiants chiffrés à l'écriture, jamais relus en clair dans une réponse ;
  - clés obligatoires et facultatives par opérateur validées côté serveur, clés inconnues
    refusées (`422`) ;
  - `test` : appel réel à l'opérateur, `lastVerificationMessage` **reformulé** en français, sans
    réponse brute, URL interne ni fragment de secret ;
  - `test` limité en débit (l'appel coûte un aller-retour vers l'opérateur) ;
  - `disable` : irréversible depuis le front, tracé dans l'audit ; un compte `DISABLED` ne peut
    plus initier de paiement ni de remboursement ;
  - un compte en `TEST` ne doit jamais traiter un paiement `LIVE`, et inversement.

### 3.6 Paiements (§3, §4)

- **Liste** : `size ≤ 1000`, `sort` limité à `createdAt` et `amount` (liste blanche ; tout
  autre champ → `400`, pour éviter le tri sur une colonne sensible ou inexistante). La
  recherche `q` passe par des paramètres liés, jamais par concaténation SQL ; les caractères
  `%` et `_` sont échappés.
- **Stats** : `from`/`to` obligatoires et bornés (période ≤ 366 jours) pour éviter les
  agrégations sur tout l'historique. Série `daily` dans le fuseau de l'organisation.
- **Création** `POST /dashboard/payments` :
  - `providerAccountId` doit appartenir au tenant **et** être `ACTIVE` ;
  - `amount` entier, `10000 ≤ amount ≤ 200000000` (bornes en configuration serveur) ;
  - `payerPhone` validé et normalisé par `PhoneNumbers` (E.164) ;
  - `expiresInMinutes ∈ {15, 60, 1440}` ;
  - `description` ≤ 140 caractères, caractères de contrôle retirés (elle est affichée au
    client final par l'opérateur) ;
  - `environment`, `currency` et `provider` **déduits du compte prestataire**, jamais lus dans
    la requête ;
  - idempotence : accepter un en-tête `Idempotency-Key` (déjà autorisé en CORS) et réutiliser
    `IdempotencyService`. Voir 5.1.
- Statut final (`SUCCESS`, `FAILED`) **uniquement** par webhook signé ou réconciliation auprès
  de l'opérateur. Aucune route ne permet au client de changer un statut.

### 3.7 Remboursements (§4.6)

Ordre des contrôles, tous côté serveur, dans une transaction avec verrou sur le paiement :

1. le paiement appartient au tenant (sinon `404`) ;
2. statut `SUCCESS` ou `PARTIALLY_REFUNDED` (sinon `409`) ;
3. aucun remboursement `PENDING` en cours sur ce paiement (sinon `409`) ;
4. compte prestataire du paiement toujours utilisable ;
5. `remaining = amount − Σ(remboursements SUCCESS et PENDING)` ;
6. `amount` absent → `remaining` ; sinon `0 < amount ≤ remaining` (sinon `422`) ;
7. Wave et `amount ≠ remaining` → `422` ;
8. création du `Refund` `PENDING`, commit, appel à l'opérateur, mise à jour du statut.

Le `reason` est obligatoire, ≤ 500 caractères. Une réponse de l'opérateur ambiguë (délai
dépassé) laisse le remboursement `PENDING` et le confie à la réconciliation : **ne jamais**
supposer l'échec et laisser relancer, au risque de rembourser deux fois.

### 3.8 Remboursements programmés (§5)

- Création : mêmes contrôles qu'en 3.7 (étapes 1, 2, 5, 6, 7) plus :
  `now + 1 min ≤ scheduledAt ≤ now + 90 jours` (le front impose 5 min, le serveur applique sa
  propre borne) ; un seul `SCHEDULED` par paiement, garanti par **index unique partiel** en
  base (`WHERE status = 'SCHEDULED'`), pas seulement par un contrôle applicatif.
- `createdBy` : `sub` stocké pour l'audit, nom affichable renvoyé.
- Exécution : réutiliser `DueWorkClaimer` (réservation `FOR UPDATE SKIP LOCKED`) pour qu'une
  programmation ne soit jamais exécutée deux fois, même avec plusieurs instances. L'exécution
  appelle **le même service** que 3.7, avec le tenant de la programmation positionné
  explicitement. Échec → `FAILED` + `failureReason` en français, aucun débit.
- Annulation : transition `SCHEDULED → CANCELLED` atomique (`UPDATE … WHERE status =
  'SCHEDULED'`) pour éviter la course avec l'exécution ; `0 ligne` → `409`.
- Les droits de l'auteur sont **revérifiés à l'exécution** : si l'auteur n'est plus `OWNER` ni
  `ADMIN` de l'organisation, ou si l'organisation est suspendue, la programmation échoue.

### 3.9 Formulaire de contact — `POST /public/leads` (§2.9)

- Limitation de débit par IP, tailles maximales sur tous les champs, listes fermées pour
  `sector`, `monthlyVolume`, `subject`.
- Contenu traité comme non fiable : échappé dans tout e-mail ou back-office qui l'affiche.
- Réponse `202` sans corps, identique que l'e-mail soit déjà connu ou non.

---

## 4. Transverse

| Sujet | Exigence |
|---|---|
| Limitation de débit | Redis (déjà présent). Clés `ip`, `email`, `sub` selon la route. `429` + `Retry-After` + message français. |
| En-têtes | `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`, `Cache-Control: no-store` sur les réponses authentifiées. |
| Tailles | Corps JSON limité (ex. 64 Ko). Toutes les chaînes ont un `@Size`. |
| Journaux | Masquer téléphones (`+22177****567`), jamais de secret ni de jeton. `correlationId` sur chaque ligne. |
| Audit | Table d'audit append-only pour : connexion réussie/échouée, remboursement, programmation, annulation, création/désactivation de compte prestataire, changement de rôle, accès super admin. |
| Swagger | Désactivé en `prod` (`springdoc.api-docs.enabled: false`). |
| Dépendances | Pas de nouvelle dépendance sans version figée ; vérifier les CVE avant ajout. |

---

## 5. Écarts au contrat à communiquer au front

| # | Sujet | Demande au front |
|---|---|---|
| 5.1 | Idempotence | Envoyer `Idempotency-Key: <uuid>` sur `POST /dashboard/payments`, `POST …/refunds` et `POST …/scheduled-refunds`, même clé si l'utilisateur relance après une erreur réseau. Le serveur reste protégé sans (verrou, 3.7), mais la clé évite un second paiement en cas de relance. |
| 5.2 | Inscription `409` | Accepté, avec limitation de débit. Si l'énumération d'e-mails devient un problème, passer à un `202` systématique et un e-mail « vous avez déjà un compte ». |
| 5.3 | `refresh` / `logout` | Le serveur exige un en-tête `Origin` connu. Les appels doivent venir du navigateur avec `credentials: 'include'`. |
| 5.4 | Dates `from`/`to` | Le serveur acceptera aussi les `Instant` avec `Z` (§7.3) ; passer à ce format dès que possible. |
| 5.5 | Rôles | `VIEWER` et `DEVELOPER` reçoivent `403` sur les remboursements et la gestion des prestataires. Masquer ces boutons selon `organization.role`. |
| 5.6 | `429` | Afficher le `message` et respecter `Retry-After`. |

---

## 6. Ordre d'implémentation proposé

1. **Correctifs critiques** C1 à C5 et I2, I4, I5, I6, I8, avec leurs tests.
2. Table `users`, `GET /me`, contrôle « e-mail vérifié ».
3. `login` / `refresh` / `logout` (débloque le front réel), limitation de débit.
4. Inscription et vérification de l'e-mail.
5. Contrôleurs organisation, profil, préférences, formule, prestataires (`test`, `disable`),
   fin d'onboarding.
6. `POST /dashboard/payments` avec idempotence.
7. Remboursements programmés et leur tâche planifiée.
8. Série `daily`, plafond de pagination, export.

## 7. Tests obligatoires par route

- `401` sans jeton ou jeton expiré ; `403` rôle insuffisant ; `404` ressource d'une autre
  organisation ; `403` e-mail non vérifié quand c'est exigé.
- Validation : chaque règle du contrat a un test de refus.
- Argent : test de concurrence (deux remboursements simultanés → un seul réussit) ; test
  d'exécution concurrente d'une programmation (une seule exécution).
- Aucune réponse ne contient un secret en clair (test sur le JSON des comptes prestataires).
