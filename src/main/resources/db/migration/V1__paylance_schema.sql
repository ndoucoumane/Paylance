-- Paylance — schéma de référence (base repartie de zéro).
--
-- Multi-tenancy par discriminant : chaque table métier porte organization_id (NOT NULL),
-- filtré automatiquement par Hibernate (@TenantId). Seule la table organizations n'est pas
-- filtrée : c'est le tenant lui-même.

CREATE SEQUENCE paylance_txn_seq START WITH 1 INCREMENT BY 1;

-- ── Organisations et membres ─────────────────────────────────────────────────

CREATE TABLE organizations (
    id                VARCHAR(36)  PRIMARY KEY,
    name              VARCHAR(150) NOT NULL,
    slug              VARCHAR(80)  NOT NULL,
    country           VARCHAR(2)   NOT NULL,
    default_currency  VARCHAR(3)   NOT NULL DEFAULT 'XOF',
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_organizations_slug UNIQUE (slug)
);

CREATE TABLE organization_members (
    id                VARCHAR(36)  PRIMARY KEY,
    organization_id   VARCHAR(36)  NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    keycloak_user_id  VARCHAR(100) NOT NULL,
    email             VARCHAR(255),
    role              VARCHAR(20)  NOT NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_organization_members_user UNIQUE (organization_id, keycloak_user_id),
    CONSTRAINT chk_organization_members_role CHECK (role IN ('OWNER', 'ADMIN', 'DEVELOPER', 'VIEWER'))
);

CREATE INDEX idx_organization_members_user ON organization_members (keycloak_user_id);

-- ── Applications et clés API ─────────────────────────────────────────────────

CREATE TABLE applications (
    id                        VARCHAR(36)  PRIMARY KEY,
    organization_id           VARCHAR(36)  NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    name                      VARCHAR(150) NOT NULL,
    slug                      VARCHAR(80)  NOT NULL,
    environment               VARCHAR(10)  NOT NULL,
    callback_url              VARCHAR(500),
    success_url               VARCHAR(500),
    error_url                 VARCHAR(500),
    signing_secret_encrypted  TEXT         NOT NULL,
    active                    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at                TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_applications_slug UNIQUE (organization_id, slug),
    CONSTRAINT chk_applications_environment CHECK (environment IN ('TEST', 'LIVE'))
);

CREATE TABLE api_keys (
    id               VARCHAR(36)  PRIMARY KEY,
    organization_id  VARCHAR(36)  NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    application_id   VARCHAR(36)  NOT NULL REFERENCES applications (id) ON DELETE CASCADE,
    label            VARCHAR(150),
    prefix           VARCHAR(40)  NOT NULL,
    last_four        VARCHAR(4)   NOT NULL,
    key_hash         VARCHAR(100) NOT NULL,
    environment      VARCHAR(10)  NOT NULL,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    expires_at       TIMESTAMP,
    last_used_at     TIMESTAMP,
    revoked_at       TIMESTAMP,
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_api_keys_prefix UNIQUE (prefix)
);

CREATE INDEX idx_api_keys_application ON api_keys (application_id);

-- ── Comptes prestataires et clients finaux ──────────────────────────────────

CREATE TABLE provider_accounts (
    id                         VARCHAR(36)  PRIMARY KEY,
    organization_id            VARCHAR(36)  NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    provider                   VARCHAR(30)  NOT NULL,
    label                      VARCHAR(150) NOT NULL,
    environment                VARCHAR(10)  NOT NULL,
    credentials_encrypted      TEXT         NOT NULL,
    webhook_secret_encrypted   TEXT,
    status                     VARCHAR(30)  NOT NULL DEFAULT 'PENDING_VERIFICATION',
    last_verified_at           TIMESTAMP,
    last_verification_message  VARCHAR(500),
    created_at                 TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT chk_provider_accounts_status CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'DISABLED'))
);

CREATE INDEX idx_provider_accounts_lookup ON provider_accounts (organization_id, provider, environment, status);

CREATE TABLE customers (
    id               VARCHAR(36)  PRIMARY KEY,
    organization_id  VARCHAR(36)  NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    phone            VARCHAR(20)  NOT NULL,
    name             VARCHAR(100),
    email            VARCHAR(255),
    external_id      VARCHAR(100),
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_customers_phone UNIQUE (organization_id, phone)
);

-- ── Paiements ────────────────────────────────────────────────────────────────

CREATE TABLE payments (
    id                       VARCHAR(36)  PRIMARY KEY,
    version                  BIGINT       NOT NULL DEFAULT 0,
    organization_id          VARCHAR(36)  NOT NULL REFERENCES organizations (id),
    application_id           VARCHAR(36)  REFERENCES applications (id),
    provider_account_id      VARCHAR(36)  NOT NULL REFERENCES provider_accounts (id),
    customer_id              VARCHAR(36)  REFERENCES customers (id),
    environment              VARCHAR(10)  NOT NULL,
    payment_number           VARCHAR(30),
    idempotency_key          VARCHAR(100) NOT NULL,
    correlation_id           VARCHAR(64)  NOT NULL,
    external_reference       VARCHAR(100),
    callback_url             VARCHAR(500),
    success_url              VARCHAR(500),
    error_url                VARCHAR(500),
    metadata                 TEXT,
    provider                 VARCHAR(30)  NOT NULL,
    provider_transaction_id  VARCHAR(200),
    provider_raw_response    TEXT,
    amount                   BIGINT       NOT NULL,
    currency                 VARCHAR(10)  NOT NULL DEFAULT 'XOF',
    payer_phone              VARCHAR(20)  NOT NULL,
    payer_name               VARCHAR(100),
    actual_payer_phone       VARCHAR(20),
    status                   VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    failure_reason           VARCHAR(500),
    callback_attempts        INTEGER      NOT NULL DEFAULT 0,
    last_callback_at         TIMESTAMP,
    callback_success         BOOLEAN      NOT NULL DEFAULT FALSE,
    expires_at               TIMESTAMP,
    paid_at                  TIMESTAMP,
    created_at               TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at               TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_payments_payment_number UNIQUE (payment_number),
    -- Une même clé d'idempotence peut être réutilisée par deux applications différentes ;
    -- NULLS NOT DISTINCT : les paiements sans application partagent un même espace de clés.
    CONSTRAINT uq_payments_idempotency UNIQUE NULLS NOT DISTINCT (organization_id, application_id, idempotency_key),
    CONSTRAINT chk_payments_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_payments_org_created      ON payments (organization_id, created_at DESC);
CREATE INDEX idx_payments_status_created   ON payments (status, created_at);
CREATE INDEX idx_payments_provider_tx      ON payments (provider_account_id, provider_transaction_id);
CREATE INDEX idx_payments_external_ref     ON payments (organization_id, external_reference);
CREATE INDEX idx_payments_correlation_id   ON payments (correlation_id);
CREATE INDEX idx_payments_customer         ON payments (customer_id);

CREATE TABLE payment_status_history (
    id               VARCHAR(36)  PRIMARY KEY,
    organization_id  VARCHAR(36)  NOT NULL REFERENCES organizations (id),
    payment_id       VARCHAR(36)  NOT NULL REFERENCES payments (id) ON DELETE CASCADE,
    from_status      VARCHAR(30),
    to_status        VARCHAR(30)  NOT NULL,
    reason           VARCHAR(500),
    triggered_by     VARCHAR(30)  NOT NULL,
    metadata         TEXT,
    created_at       TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_status_history_payment ON payment_status_history (payment_id);

CREATE TABLE payment_logs (
    id               VARCHAR(36)  PRIMARY KEY,
    organization_id  VARCHAR(36)  NOT NULL REFERENCES organizations (id),
    payment_id       VARCHAR(36)  REFERENCES payments (id) ON DELETE CASCADE,
    correlation_id   VARCHAR(64),
    level            VARCHAR(10)  NOT NULL,
    event            VARCHAR(100) NOT NULL,
    message          TEXT,
    payload          TEXT,
    created_at       TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_logs_payment     ON payment_logs (payment_id);
CREATE INDEX idx_payment_logs_correlation ON payment_logs (correlation_id);

CREATE TABLE refunds (
    id                     VARCHAR(36)  PRIMARY KEY,
    organization_id        VARCHAR(36)  NOT NULL REFERENCES organizations (id),
    payment_id             VARCHAR(36)  NOT NULL REFERENCES payments (id) ON DELETE CASCADE,
    amount                 BIGINT       NOT NULL,
    reason                 VARCHAR(500),
    status                 VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    provider_refund_id     VARCHAR(200),
    provider_raw_response  TEXT,
    failure_reason         VARCHAR(500),
    requested_by           VARCHAR(150),
    created_at             TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at             TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT chk_refunds_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_refunds_payment ON refunds (payment_id);
CREATE INDEX idx_refunds_status  ON refunds (status);

CREATE TABLE scheduled_payments (
    id                   VARCHAR(36)  PRIMARY KEY,
    organization_id      VARCHAR(36)  NOT NULL REFERENCES organizations (id),
    provider_account_id  VARCHAR(36)  NOT NULL REFERENCES provider_accounts (id),
    provider             VARCHAR(30)  NOT NULL,
    amount               BIGINT       NOT NULL,
    payer_phone          VARCHAR(20)  NOT NULL,
    scheduled_at         TIMESTAMP    NOT NULL,
    external_reference   VARCHAR(100),
    note                 VARCHAR(500),
    status               VARCHAR(30)  NOT NULL DEFAULT 'SCHEDULED',
    payment_id           VARCHAR(36)  REFERENCES payments (id),
    failure_reason       VARCHAR(500),
    executed_at          TIMESTAMP,
    created_at           TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT chk_scheduled_payments_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_scheduled_payments_due ON scheduled_payments (status, scheduled_at);

-- ── Webhooks entrants ────────────────────────────────────────────────────────

CREATE TABLE webhook_events (
    id                   VARCHAR(36)  PRIMARY KEY,
    organization_id      VARCHAR(36)  NOT NULL REFERENCES organizations (id),
    provider_account_id  VARCHAR(36)  NOT NULL REFERENCES provider_accounts (id),
    provider             VARCHAR(30)  NOT NULL,
    provider_event_id    VARCHAR(200),
    payment_id           VARCHAR(36)  REFERENCES payments (id) ON DELETE SET NULL,
    signature            VARCHAR(500),
    signature_valid      BOOLEAN,
    raw_payload          TEXT         NOT NULL,
    status               VARCHAR(30)  NOT NULL DEFAULT 'RECEIVED',
    processing_error     VARCHAR(1000),
    correlation_id       VARCHAR(64),
    created_at           TIMESTAMP    NOT NULL DEFAULT now(),
    processed_at         TIMESTAMP
);

CREATE INDEX idx_webhook_events_account_event ON webhook_events (provider, provider_account_id, provider_event_id);
CREATE INDEX idx_webhook_events_payment       ON webhook_events (payment_id);
