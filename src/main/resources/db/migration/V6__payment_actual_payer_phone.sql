-- Numéro ayant effectivement réglé le paiement côté provider (peut différer de payer_phone,
-- déclaré par la plateforme à la création de la commande). Renseigné uniquement quand le
-- provider le communique (Orange Money via subscriberMsisdn) — reste NULL pour Wave, qui
-- ne restitue jamais l'identité du payeur.
ALTER TABLE payments
    ADD COLUMN actual_payer_phone VARCHAR(20);
