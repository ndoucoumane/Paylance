-- Remplace le système de compteurs par plateforme (V2) par une référence de transaction
-- structurée et globale : PL-YYMMDD-SSSSSS-C (C = chiffre de contrôle Luhn), générée à
-- partir d'une séquence Postgres unique — atomique nativement, sans verrou applicatif.

DROP TABLE IF EXISTS payment_number_counters;

CREATE SEQUENCE paylance_txn_seq START WITH 1 INCREMENT BY 1;

-- Élargi pour absorber la croissance de la séquence au-delà de 6 chiffres sur le long terme.
ALTER TABLE payments ALTER COLUMN payment_number TYPE VARCHAR(30);
