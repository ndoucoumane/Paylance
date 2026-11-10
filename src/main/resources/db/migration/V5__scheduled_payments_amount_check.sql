-- Contrainte DB : le montant doit rester strictement positif (filet au-delà de la validation API).
ALTER TABLE scheduled_payments
    ADD CONSTRAINT chk_scheduled_payments_amount_positive CHECK (amount > 0);
