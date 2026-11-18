# Backend Paylance : ce qui est prêt pour le front (18/11/2026)

Réponse au contrat `paylance-front/docs/contrat-api-backend.md`.

## 0. Mise à jour : réponses aux écarts signalés par le front

| Écart signalé | Correction backend |
|---|---|
| CORS bloquant sur `5173` | Origine par défaut : `http://localhost:5173`. Les cookies sont autorisés (`Access-Control-Allow-Credentials: true`), avec des origines explicites seulement. En-tête `Accept` ajouté. |
| Recherche `q` sensible aux espaces, jokers non échappés | Recherche insensible à la casse **et aux espaces** : « 77 123 » trouve `+221771234567`. `%`, `_` et `\` sont cherchés littéralement. 100 caractères maximum. Idem pour les remboursements et les versements. |
| `400` = « Requête invalide » | `message` explique quoi corriger, ex. « Indiquez le motif du remboursement. ». Plusieurs erreurs sont mises bout à bout. `details` garde le détail par champ. |
| `403` = « Accès refusé » | « Vous n'avez pas les droits nécessaires pour cette action. ». Si l'e-mail n'est pas vérifié : « Vérifiez votre adresse e-mail pour accéder à cet espace. ». Si l'utilisateur n'est pas membre : « Vous n'avez pas accès à cette organisation. ». |
| `502` parle de « provider » | « Le prestataire de paiement ne répond pas pour le moment. Réessayez dans quelques instants. » |
| `refundableAmount` absent | **Ajouté à chaque `Payment`** (liste et détail), calculé par le serveur avec les mêmes règles que le remboursement : remboursements réussis **et en cours** déduits, Wave total uniquement, `0` si le statut n'est pas remboursable. **C'est la valeur à afficher : ne plus l'estimer côté front.** |
| Champs nuls omis | `Payment`, `Refund`, historique, statistiques, `Organization`, `ProviderAccount` et `ErrorResponse` renvoient désormais `null` explicitement. |
| Fuseau de `from` / `to` | La JVM est en UTC dans tous les cas, pas seulement au lancement par `main()`. `from` et `to` acceptent **les deux formats** : sans fuseau (lu en UTC) et avec `Z` ou un décalage. Le front peut passer à `toISOString()` (§7.3 du contrat). Une date illisible → `400` « Le paramètre « from » est invalide. ». |
| `requestedBy` = `USER:<email>` | Vaut maintenant le **nom affichable** (« Awa Diop »), lu dans le jeton Keycloak : `given_name` + `family_name`, sinon `name`, sinon e-mail. Les remboursements créés avant cette correction gardent l'ancienne valeur. |
| `timestamp` sans fuseau | `Instant` UTC avec `Z`. `correlationId` est renseigné aussi sur les `401` et `403`. |
| Taille de page non plafonnée | Plafond **1000**. `sort` est limité à `createdAt` et `amount` ; un autre champ → `400`. |
| Clé `credentials` inconnue : 400 ou 422 ? | **`422`**, conformément à `contrat-api-securite.md`. Même chose pour une clé obligatoire manquante. |
| *(trouvé en corrigeant)* accents corrompus dans les `401` et `403` | Ces réponses sont maintenant en UTF-8. |

Format de la page de résultats : sérialisation Spring classique
(`{ "content": [...], "totalElements": …, "number": …, "size": … }`).

**Documents de référence côté backend, à lire :**

- `../paylance-backend/docs/contrat-api-securite.md` : déclinaison du contrat route par
  route, matrice des droits, exigences de sécurité, et écarts au contrat (section 5).
- `../paylance-backend/CLAUDE.md` : règles du backend.

**Principe à garder en tête : le backend est la seule source de vérité.** Les validations du
front servent au confort de saisie. Le serveur refait tous les contrôles (formats, bornes,
droits, statuts, soldes) et sa réponse fait foi. Le front ne doit rien déduire localement qui
contredise une réponse du serveur : il affiche le `message` reçu.

---

## 1. Routes disponibles

Toutes exigent `Authorization: Bearer <jeton Keycloak>`. Les routes `/dashboard/**` exigent
aussi `X-Organization-Id`.

| Route | État | Remarque |
|---|---|---|
| `GET /api/v1/me` | 🆕 prêt (version minimale) | Voir §2. **Débloque le mode réel.** |
| `GET /api/v1/organizations` | ✅ prêt | Ordre stable : la plus ancienne adhésion en premier. |
| `POST /api/v1/organizations` | ✅ prêt | `{ name, country, defaultCurrency? }`. Voir §3. |
| `GET /api/v1/dashboard/provider-accounts` | ✅ prêt | Comptes `DISABLED` exclus. |
| `POST /api/v1/dashboard/provider-accounts` | ✅ prêt | Statut initial `PENDING_VERIFICATION`. |
| `POST /api/v1/dashboard/provider-accounts/{id}/test` | 🆕 prêt | Renvoie le compte à jour ; un succès le passe `ACTIVE`. |
| `POST /api/v1/dashboard/provider-accounts/{id}/disable` | 🆕 prêt | Renvoie le compte `DISABLED`. |
| `GET /api/v1/dashboard/payments`, `/stats`, `/{id}`, `/{id}/history` | ✅ prêt | Inchangé. |
| `GET` et `POST /api/v1/dashboard/payments/{id}/refunds` | ✅ prêt, **règles durcies** | Voir §4. |

Pas encore disponibles, laisser `notAvailable` : inscription, vérification de l'e-mail par
code, profil d'entreprise complet, préférences, formules, fin d'onboarding,
`POST /dashboard/payments`, remboursements programmés, série `daily`, et les routes de
connexion `/public/auth/*`. **La connexion reste sur la page Keycloak (`keycloak-js`, PKCE)
pour l'instant.**

---

## 2. `GET /api/v1/me` (version minimale)

Réponse conforme au type `AccountSnapshot` :

```json
{
  "user": { "id": "…", "firstName": "Awa", "lastName": "Diop", "email": "…", "emailVerified": true },
  "organization": { "id": "…", "name": "…", "slug": "…", "country": "SN", "defaultCurrency": "XOF",
                    "status": "ACTIVE", "role": "OWNER", "createdAt": "2026-11-18T08:00:00Z" },
  "progress": { "currentStep": "DONE",
                "completedSteps": ["COMPANY", "PREFERENCES", "PLAN", "PROVIDERS", "DONE"],
                "providersSkipped": true }
}
```

- `user` vient du jeton Keycloak validé. `emailVerified` est la même valeur que celle que le
  backend utilise pour autoriser l'accès.
- `organization` est **toujours présent**, à `null` s'il n'y a pas d'organisation **ou si
  l'e-mail n'est pas vérifié**.
- `progress` est simplifié tant que les étapes intermédiaires n'ont pas de route :
  - sans organisation : `{ "currentStep": "COMPANY", "completedSteps": [], "providersSkipped": false }` ;
  - avec organisation : `DONE`. L'utilisateur arrive directement sur `/app` après la création de l'entreprise.
  - `providersSkipped` vaut `true` tant qu'aucun compte prestataire n'est `ACTIVE`.
- Réponse en `Cache-Control: no-store`.

**À modifier dans `liveAccountApi.ts` :**

```ts
async getSnapshot() {
  const kc = await keycloak();
  if (!kc.authenticated) return null;
  const snapshot = await apiRequest<AccountSnapshot>('/api/v1/me', { token: await token() });
  organizationId = snapshot.organization?.id ?? null;
  return snapshot;
},
```

**Après la création de l'organisation :** rappeler `getSnapshot()`. Ne pas construire la
progression localement.

---

## 3. Organisations et comptes prestataires

**`POST /api/v1/organizations`**

- `name` : 2 à 150 caractères.
- `country` : uniquement `SN, CI, ML, BF, BJ, TG, NE, GN, CM, FR`. Sinon `422` « Ce pays
  n'est pas encore pris en charge. ».
- `defaultCurrency` : facultatif. Il est déduit du pays (`XOF` pour l'UEMOA, `XAF` pour `CM`,
  `GNF` pour `GN`, `EUR` pour `FR`). Valeurs admises : `XOF, XAF, GNF, EUR, USD`.
- Les champs du profil complet (`legalName`, `sector`, `phone`, `website`, `size`) ne sont pas
  encore stockés : `PUT /organizations/current/profile` reste à faire.

**Comptes prestataires**

- Les secrets sont masqués au format du contrat : `••••` suivi des 4 derniers caractères. Ils
  ne sont jamais renvoyés en clair. `webhookUrl` est fourni.
- **Une clé `credentials` inconnue est refusée** (`422`, message affichable). Envoyer
  uniquement les clés de `referenceData.ts`.
- `lastVerificationMessage` après un échec : message français générique (« Connexion refusée
  par le prestataire… »). Le détail technique reste dans les journaux du serveur.
- `test` sur un compte désactivé : `409` « Ce compte prestataire est désactivé. ».

**À brancher dans `liveAccountApi.ts`** (vérifier la signature dans `accountApi.ts`) :

```ts
async testProviderAccount(id) {
  return apiRequest<ProviderAccount>(`/api/v1/dashboard/provider-accounts/${encodeURIComponent(id)}/test`, {
    method: 'POST', token: await token(), organizationId: await currentOrganizationId(),
  });
},
async disableProviderAccount(id) {
  return apiRequest<ProviderAccount>(`/api/v1/dashboard/provider-accounts/${encodeURIComponent(id)}/disable`, {
    method: 'POST', token: await token(), organizationId: await currentOrganizationId(),
  });
},
```

---

## 4. Remboursements : règles serveur

Le serveur applique maintenant toutes les règles, y compris sous requêtes simultanées
(double clic, deux onglets) :

| Cas | Statut | `message` |
|---|---|---|
| Statut non remboursable | `409` | « Impossible de rembourser un paiement au statut remboursé. » (libellé français du statut) |
| Un remboursement est déjà en cours sur ce paiement | `409` | « Un remboursement est déjà en cours pour ce paiement. Attendez sa confirmation avant d'en demander un autre. » |
| Montant ≤ 0 ou > solde | `422` | « Le montant dépasse le solde remboursable. » |
| Partiel sur Wave | `422` | « Wave ne permet que le remboursement total d'un paiement. » |
| Compte prestataire non actif | `422` | « Ce compte prestataire n'est pas actif. » |

- **La réponse `201` peut avoir `status = PENDING`.** C'est le cas quand l'opérateur n'a pas
  confirmé ; le montant reste réservé. Afficher « Remboursement envoyé », comme prévu par le
  contrat, sans proposer de relancer.
- Refus de l'opérateur : `status = FAILED`, `failureReason` = « Le prestataire a refusé le
  remboursement. ».
- Après un remboursement, toujours relire le paiement et ses remboursements. Le solde à
  afficher est `refundableAmount`. Ne pas le recalculer localement pour décider d'autoriser une action.

---

## 5. Droits

| Action | Rôles autorisés |
|---|---|
| Lecture du dashboard, liste des comptes prestataires | tous les membres |
| Rembourser, programmer ou annuler un remboursement | `OWNER`, `ADMIN` |
| Créer, tester, désactiver un compte prestataire | `OWNER`, `ADMIN` |
| Créer un paiement depuis le dashboard (à venir) | `OWNER`, `ADMIN` |

- `DEVELOPER` et `VIEWER` reçoivent `403` sur ces actions. **À faire : masquer les boutons
  selon `organization.role`.** Le serveur reste le garde-fou.
- **E-mail non vérifié** : `403` sur `/organizations` et `/dashboard/**`. Seul `GET /me`
  répond.

---

## 6. Points d'attention pour les tests en mode réel

1. Les utilisateurs Keycloak de test doivent avoir **« Email verified » activé**. Sinon ils
   restent bloqués sur `/verify-email`, car la vérification par code n'existe pas encore.
2. Le jeton doit contenir le claim `email_verified` : scope `email` par défaut sur le client
   public du front.
3. Les rôles Keycloak (realm) ne donnent **aucun** droit dans une organisation. Seul
   `paylance_super_admin` est reconnu. Les rôles `OWNER`, `ADMIN`, etc. viennent de la base.
4. Origine du front autorisée en CORS : `http://localhost:5173` par défaut
   (`PAYLANCE_DASHBOARD_ORIGINS`).

## 7. Écarts connus, encore à corriger côté backend

Aucun écart de format connu sur les routes listées en §1. Restent à faire : les routes de la
liste « Pas encore disponibles » (§1).

Prévenez le backend si un format reçu ne correspond pas au contrat. **Ne contournez pas côté
front une règle de sécurité du serveur.**
