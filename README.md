# Paylance

Middleware de paiement interne : couche invisible entre les plateformes internes
(Afrisurgelée, Kssid, Fleet360) et les providers mobile money (Wave,
Orange Money). Paylance centralise, sécurise et orchestre tous les flux de paiement,
avec traçabilité complète de chaque transaction.

## Stack

- Java 17 · Spring Boot 3.3 (Web, Data JPA, Security, Validation, Actuator, WebFlux pour WebClient)
- PostgreSQL 16 + Flyway (migrations versionnées)
- Redis 7 (verrou d'idempotence)
- Wave API · Orange Money API
- Logs structurés JSON (logstash-logback-encoder) + Actuator/Prometheus

## Architecture

```
com.paylance
├── domain/{entity,enums}     Modèle métier (Payment, Refund, WebhookEvent, PaymentLog...)
├── repository/                Spring Data JPA
├── provider/                  Intégrations Wave / Orange Money derrière PaymentProviderClient
├── idempotency/                Verrou Redis anti-doublon sur les requêtes d'initiation
├── security/                   Auth API key par plateforme (BCrypt) + auth admin
├── service/                    Orchestration (Payment, Webhook, Callback, Reconciliation, Log)
├── web/{controller,dto,exception}   API REST + gestion d'erreurs centralisée
└── config/                     WebClient, Async/Retry, Scheduling, OpenAPI
```

### Cycle de vie d'un paiement

1. Une plateforme appelle `POST /api/v1/payments` (auth `X-Platform` / `X-API-Key`).
2. Paylance pose un verrou Redis sur `(platform, idempotencyKey)`, crée le `Payment` (PENDING),
   puis appelle le provider (`PaymentProviderClient.initiate`).
3. Le provider notifie Paylance via webhook (`POST /api/v1/webhooks/wave|orange-money`),
   signature HMAC vérifiée avant tout traitement.
4. Paylance transitionne le statut (`PaymentStatusHistory` + `PaymentLog` systématiques),
   puis notifie la plateforme cliente sur son `callbackUrl` (retry + backoff exponentiel).
5. Un job planifié (`ReconciliationService`) reprend les paiements restés PENDING/PROCESSING
   trop longtemps et interroge directement le provider (filet de sécurité anti-webhook-perdu).

Chaque étape est tracée dans `payment_logs` (persisté) et dans les logs applicatifs JSON
(corrélés via `correlationId`, propagé de bout en bout).

---

## Guide de démarrage — pour les développeurs qui rejoignent le projet

### 0. Prérequis

- Java 17, Maven, Docker (+ Docker Compose)
- `psql` accessible via `docker exec` (pas besoin de l'installer en local, on passe par le conteneur)

### 1. Configuration initiale

```bash
cp .env.example .env
```

⚠️ **Piège à connaître** : `.env` est "sourcé" comme un script shell par `make run`/`make dev`/`make migrate`.
Toute valeur contenant un `$` (typiquement un hash BCrypt, `$2a$12$...`) **doit être entre guillemets
simples**, sinon bash essaie de l'interpréter comme une variable et corrompt la valeur :
```
ADMIN_KEY_HASH='$2a$12$xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx'
```

### 2. Démarrer l'environnement local

```bash
make db     # démarre Postgres + Redis + Adminer (http://localhost:8081)
make run    # compile et lance l'application
```

Ou en une seule commande : `make dev` (build + run).

L'application écoute sur **`http://localhost:8082`** (pas 8080 — volontairement décalé pour éviter
les collisions avec d'autres services locaux type Jenkins qui squattent souvent ce port).

Vérifier que tout tourne :
```bash
curl http://localhost:8082/actuator/health
# {"status":"UP","components":{"db":{"status":"UP"...},"redis":{"status":"UP"...}}}
```

- Documentation API interactive : `http://localhost:8082/swagger-ui.html`
- Admin base de données (Adminer) : `http://localhost:8081` (serveur `postgres`, user/password dans `.env`)

### 3. Créer une clé API pour une plateforme

Chaque plateforme cliente (`AFRISURGELEE`, `KSSID`, `FLEET360`) a sa propre clé API,
stockée hashée (BCrypt) — jamais en clair en base.

**a. Générer une clé + son hash :**
```bash
make keys
```
```
Clé API en clair (à transmettre une seule fois à la plateforme) :
pl_live_MhkSAfRSpXnrJLPsiyYMjXHJJwfYQDbbwFTClZPDXMg

Hash BCrypt (à stocker dans platform_api_keys.api_key_hash) :
$2a$12$XdXHmiOSFsxRT2wZ7iggGevu6nQx7uD0M2VMhwtDR2Bs.xJXOe8ni
```

**b. Insérer le hash en base** (une ligne par plateforme, remplacer `AFRISURGELEE` et le hash) :
```bash
docker exec -i paylance-postgres psql -U paylance -d paylance -c "
INSERT INTO platform_api_keys (id, platform, label, api_key_hash, is_active, created_at, updated_at)
VALUES (gen_random_uuid()::text, 'AFRISURGELEE', 'local-dev', '\$2a\$12\$XdXHmiOSFsxRT2wZ7iggGevu6nQx7uD0M2VMhwtDR2Bs.xJXOe8ni', true, now(), now());
"
```
⚠️ Dans le hash collé en shell, chaque `$` doit être échappé en `\$` (sinon bash les interprète, même
piège que pour `.env`).

**c. Conserver la clé en clair** (celle de l'étape a, pas le hash) — c'est elle qui sert dans les
headers `X-API-Key`. Elle n'est récupérable nulle part ailleurs une fois générée.

### 4. Initier un paiement de test

```bash
curl -X POST http://localhost:8082/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "X-Platform: AFRISURGELEE" \
  -H "X-API-Key: pl_live_MhkSAfRSpXnrJLPsiyYMjXHJJwfYQDbbwFTClZPDXMg" \
  -d '{
    "idempotencyKey": "order-'"$(date +%s)"'",
    "provider": "WAVE",
    "amount": 150000,
    "payerPhone": "+221771234567",
    "payerName": "Awa Diop",
    "externalReference": "order-42",
    "callbackUrl": "https://afrisurgelee.test/paylance/callback"
  }'
```

- `idempotencyKey` doit être unique par plateforme à chaque nouvel essai — une requête avec la même
  clé renvoie le paiement déjà créé au lieu d'en créer un nouveau (idempotence garantie).
- `amount` est en **centimes** (150000 = 1500 XOF), jamais de virgule/décimal.
- Sans vrais credentials Wave/Orange Money dans `.env` (valeurs `change-me` par défaut), l'appel au
  provider échoue en `502 Bad Gateway` — **comportement attendu** en local. Le paiement est quand même
  créé et tracé (statut `FAILED`), ce qui permet de tester tout le reste du pipeline.

**Consulter le statut :**
```bash
curl http://localhost:8082/api/v1/payments/<paymentId> \
  -H "X-Platform: AFRISURGELEE" \
  -H "X-API-Key: pl_live_MhkSAfRSpXnrJLPsiyYMjXHJJwfYQDbbwFTClZPDXMg"
```

**Rembourser** (uniquement si le paiement est `SUCCESS`) :
```bash
curl -X POST http://localhost:8082/api/v1/payments/<paymentId>/refunds \
  -H "Content-Type: application/json" \
  -H "X-Platform: AFRISURGELEE" \
  -H "X-API-Key: pl_live_MhkSAfRSpXnrJLPsiyYMjXHJJwfYQDbbwFTClZPDXMg" \
  -d '{"reason": "commande annulée"}'
```

### 5. Configurer et utiliser la clé admin

Les endpoints `/api/v1/admin/**` (consultation cross-plateforme, logs techniques, reconciliation
manuelle) utilisent une clé **séparée** des clés plateforme, non activée par défaut.

```bash
make keys                          # réutilise le même générateur
```
Colle le **hash** dans `.env` (entre guillemets simples, cf. piège plus haut) :
```
ADMIN_KEY_HASH='$2a$12$...'
```
Redémarre l'app (`make run`), puis utilise la **clé en clair** en header :
```bash
curl http://localhost:8082/api/v1/admin/payments/<paymentId>/logs \
  -H "X-Admin-Key: <clé en clair>"
```

| Méthode | Endpoint | Description |
|---|---|---|
| GET | `/api/v1/admin/payments/{id}` | Paiement (toutes plateformes confondues) |
| GET | `/api/v1/admin/payments/{id}/logs` | Trace technique complète (`payment_logs`) |
| GET | `/api/v1/admin/payments/{id}/history` | Historique des transitions de statut |
| POST | `/api/v1/admin/reconciliation/run` | Déclenche la reconciliation immédiatement (normalement automatique toutes les 5 min) |
| GET | `/actuator/metrics`, `/actuator/prometheus` | Métriques (protégées admin aussi) |

### 6. Tester un webhook (signature HMAC)

Les webhooks (`POST /api/v1/webhooks/wave|orange-money`) ne prennent **pas** de clé API — leur
authenticité est garantie par une signature HMAC-SHA256 sur le corps brut de la requête, vérifiée
contre `WAVE_WEBHOOK_SECRET`/`ORANGE_MONEY_WEBHOOK_SECRET` (`.env`).

```bash
PAYLOAD='{"id":"evt1","data":{"id":"<providerTransactionId d un paiement existant>","payment_status":"succeeded"}}'
SECRET="change-me"   # doit matcher WAVE_WEBHOOK_SECRET dans .env
SIG=$(echo -n "$PAYLOAD" | openssl dgst -sha256 -hmac "$SECRET" | sed 's/^.* //')

curl -X POST http://localhost:8082/api/v1/webhooks/wave \
  -H "Content-Type: application/json" \
  -H "Wave-Signature: $SIG" \
  -d "$PAYLOAD"
```
Sans signature valide → `401` (comportement attendu, c'est la protection qui fonctionne).

---

## Dépannage

| Symptôme | Cause probable | Solution |
|---|---|---|
| Réponse HTML "No valid crumb..." au lieu de JSON | Un autre service (souvent Jenkins) écoute sur le port testé | Vérifier qu'on appelle bien `:8082`, pas `:8080` |
| `Port XXXX was already in use` au démarrage | Une instance Paylance tourne déjà (IDE, terminal précédent...) | `lsof -i :8082` pour identifier le process, l'arrêter avant de relancer |
| Hash BCrypt corrompu / auth admin échoue toujours | `$` non protégé dans `.env` ou lors d'un `INSERT` psql en shell | Entourer de guillemets simples dans `.env` ; échapper en `\$` dans une commande shell |
| `401` sur `/api/v1/payments` | Clé API absente/inactive/expirée en base, ou mauvais header `X-Platform` | Vérifier `platform_api_keys.is_active` et que la clé en clair correspond bien au hash inséré |
| `409 Conflict` sur initiation | Même `idempotencyKey` réutilisée pour la même plateforme | Utiliser une nouvelle valeur à chaque test |
| `502 Bad Gateway` sur initiation/refund | Credentials Wave/Orange Money placeholder (`change-me`) | Attendu en local sans vrais credentials — le paiement est quand même créé et tracé |

---

## Build / Tests

```bash
make build   # mvn clean install -DskipTests
make test    # tests unitaires (Mockito)
mvn -Dtest=PaylanceApplicationIT test   # test d'intégration Testcontainers — nécessite Docker
```

## Sécurité

- **Plateformes → Paylance** : headers `X-Platform` + `X-API-Key`, comparés en BCrypt
  contre `platform_api_keys.api_key_hash` (jamais de clé en clair en base).
- **Providers → Paylance (webhooks)** : signature HMAC-SHA256 vérifiée sur le corps brut
  de la requête avant tout parsing, comparaison en temps constant.
- **Admin/reconciliation** : header `X-Admin-Key` séparé, non lié à une plateforme.

## Points d'extension notés dans le code

- `PlatformCallbackSender` : alerting (Slack/email/PagerDuty) à brancher sur `@Recover`
  quand toutes les tentatives de callback échouent.
- `ReconciliationService` : idem sur le bloc `catch` — actuellement log ERROR structuré.
- `OrangeMoneyClient.refund` : l'API publique ne documente pas de remboursement automatisé
  standard, à brancher sur l'accord marchand une fois disponible.
- Les DTOs/endpoints Wave et Orange Money sont basés sur la documentation publique ; à
  confirmer/ajuster avec les credentials réels avant mise en production.
