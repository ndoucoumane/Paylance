-- Paylance initial schema

CREATE TABLE platform_api_keys (
    id              VARCHAR(36) PRIMARY KEY,
    platform        VARCHAR(30)  NOT NULL,
    label           VARCHAR(150),
    api_key_hash    VARCHAR(200) NOT NULL,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    last_used_at    TIMESTAMP,
    expires_at      TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_platform_api_keys_platform UNIQUE (platform),
    CONSTRAINT uq_platform_api_keys_hash UNIQUE (api_key_hash)
);

CREATE TABLE payments (
    id                          VARCHAR(36) PRIMARY KEY,
    version                     BIGINT       NOT NULL DEFAULT 0,
    idempotency_key             VARCHAR(100) NOT NULL,
    correlation_id              VARCHAR(64)  NOT NULL,
    platform                    VARCHAR(30)  NOT NULL,
    external_reference          VARCHAR(100),
    callback_url                VARCHAR(500),
    metadata                    TEXT,
    provider                    VARCHAR(30)  NOT NULL,
    provider_transaction_id     VARCHAR(200),
    provider_raw_response       TEXT,
    amount                      BIGINT       NOT NULL,
    currency                    VARCHAR(10)  NOT NULL DEFAULT 'XOF',
    payer_phone                 VARCHAR(20)  NOT NULL,
    payer_name                  VARCHAR(100),
    status                      VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    failure_reason              VARCHAR(500),
    callback_attempts           INTEGER      NOT NULL DEFAULT 0,
    last_callback_at            TIMESTAMP,
    callback_success            BOOLEAN      NOT NULL DEFAULT FALSE,
    expires_at                  TIMESTAMP,
    paid_at                     TIMESTAMP,
    created_at                  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_payments_platform_idempotency UNIQUE (platform, idempotency_key)
);

CREATE INDEX idx_payments_platform       ON payments (platform);
CREATE INDEX idx_payments_status         ON payments (status);
CREATE INDEX idx_payments_external_ref   ON payments (external_reference);
CREATE INDEX idx_payments_provider_txid  ON payments (provider_transaction_id);
CREATE INDEX idx_payments_correlation_id ON payments (correlation_id);
CREATE INDEX idx_payments_created_at     ON payments (created_at);

CREATE TABLE payment_status_history (
    id             VARCHAR(36) PRIMARY KEY,
    payment_id     VARCHAR(36)  NOT NULL REFERENCES payments (id) ON DELETE CASCADE,
    from_status    VARCHAR(30),
    to_status      VARCHAR(30)  NOT NULL,
    reason         VARCHAR(500),
    triggered_by   VARCHAR(30)  NOT NULL,
    metadata       TEXT,
    created_at     TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_status_history_payment ON payment_status_history (payment_id);
CREATE INDEX idx_status_history_date    ON payment_status_history (created_at);

CREATE TABLE payment_logs (
    id              VARCHAR(36) PRIMARY KEY,
    payment_id      VARCHAR(36) REFERENCES payments (id) ON DELETE CASCADE,
    correlation_id  VARCHAR(64),
    level           VARCHAR(10)  NOT NULL,
    event           VARCHAR(100) NOT NULL,
    message         TEXT,
    payload         TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_logs_payment     ON payment_logs (payment_id);
CREATE INDEX idx_payment_logs_date        ON payment_logs (created_at);
CREATE INDEX idx_payment_logs_correlation ON payment_logs (correlation_id);

CREATE TABLE refunds (
    id                      VARCHAR(36) PRIMARY KEY,
    payment_id              VARCHAR(36)  NOT NULL REFERENCES payments (id) ON DELETE CASCADE,
    amount                  BIGINT       NOT NULL,
    reason                  VARCHAR(500),
    status                  VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    provider_refund_id      VARCHAR(200),
    provider_raw_response   TEXT,
    failure_reason          VARCHAR(500),
    requested_by            VARCHAR(150),
    created_at              TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at              TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_refunds_payment ON refunds (payment_id);
CREATE INDEX idx_refunds_status  ON refunds (status);

CREATE TABLE webhook_events (
    id                  VARCHAR(36) PRIMARY KEY,
    provider            VARCHAR(30)  NOT NULL,
    provider_event_id   VARCHAR(200),
    payment_id          VARCHAR(36) REFERENCES payments (id) ON DELETE SET NULL,
    signature           VARCHAR(500),
    signature_valid     BOOLEAN,
    raw_payload         TEXT         NOT NULL,
    status              VARCHAR(30)  NOT NULL DEFAULT 'RECEIVED',
    processing_error    VARCHAR(1000),
    correlation_id      VARCHAR(64),
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    processed_at        TIMESTAMP
);

CREATE INDEX idx_webhook_events_provider_event ON webhook_events (provider, provider_event_id);
CREATE INDEX idx_webhook_events_payment         ON webhook_events (payment_id);
CREATE INDEX idx_webhook_events_status          ON webhook_events (status);
CREATE INDEX idx_webhook_events_created_at      ON webhook_events (created_at);
