-- Paiements programmés déclenchés depuis le dashboard admin : créés au statut SCHEDULED,
-- exécutés (vrai appel provider) par le job planifié dès que scheduled_at est atteint.

CREATE TABLE scheduled_payments (
    id                  VARCHAR(36)  PRIMARY KEY,
    provider            VARCHAR(30)  NOT NULL,
    platform            VARCHAR(30)  NOT NULL,
    amount              BIGINT       NOT NULL,
    payer_phone         VARCHAR(20)  NOT NULL,
    scheduled_at        TIMESTAMP    NOT NULL,
    external_reference  VARCHAR(100),
    note                VARCHAR(500),
    status              VARCHAR(30)  NOT NULL DEFAULT 'SCHEDULED',
    payment_id          VARCHAR(36)  REFERENCES payments (id),
    failure_reason      VARCHAR(500),
    executed_at         TIMESTAMP,
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_scheduled_payments_status_due ON scheduled_payments (status, scheduled_at);
CREATE INDEX idx_scheduled_payments_platform   ON scheduled_payments (platform);
CREATE INDEX idx_scheduled_payments_payment_id ON scheduled_payments (payment_id);
