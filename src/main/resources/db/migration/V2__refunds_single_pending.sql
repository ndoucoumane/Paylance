-- Un seul remboursement en cours (PENDING) par paiement.
--
-- Le service sérialise déjà les remboursements d'un même paiement (verrou FOR UPDATE sur la
-- ligne payments). Cet index est la garantie de dernier recours : même un chemin de code qui
-- oublierait le verrou ne peut pas engager deux remboursements simultanés, donc ne peut pas
-- rembourser au-delà du montant payé.
--
-- Échoue si des doublons PENDING existent déjà : les régulariser à la main avant de migrer.
CREATE UNIQUE INDEX uq_refunds_one_pending_per_payment
    ON refunds (payment_id)
    WHERE status = 'PENDING';
