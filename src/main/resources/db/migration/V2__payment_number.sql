-- Numéro de paiement lisible (ex: PAY-AFR-0001), affiché côté dashboard/front à la place de
-- l'UUID technique. L'UUID (payments.id) reste la clé primaire et la référence de corrélation
-- avec les providers (Orange Money notamment) — ce numéro est purement d'affichage.

ALTER TABLE payments ADD COLUMN payment_number VARCHAR(20);

-- Plusieurs NULL sont autorisés par un index unique Postgres (NULL != NULL) : les paiements
-- créés avant cette migration restent sans numéro, sans violer la contrainte.
CREATE UNIQUE INDEX idx_payments_payment_number ON payments (payment_number);

-- Compteur atomique par plateforme (UPDATE ... RETURNING évite toute race condition
-- sans verrou explicite : la ligne est verrouillée le temps de la transaction).
CREATE TABLE payment_number_counters (
    platform    VARCHAR(30) PRIMARY KEY,
    next_value  BIGINT      NOT NULL DEFAULT 1
);

INSERT INTO payment_number_counters (platform, next_value) VALUES
    ('AFRISURGELEE', 1),
    ('KSSID', 1),
    ('FLEET360', 1);
