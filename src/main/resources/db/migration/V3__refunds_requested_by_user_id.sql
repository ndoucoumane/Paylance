-- requested_by devient le nom affiché au marchand (« Awa Diop ») ; l'identité vérifiable de
-- l'auteur (sub Keycloak) est conservée à part, pour l'audit. NULL pour un remboursement
-- demandé par une application (clé API) ou antérieur à cette migration.
ALTER TABLE refunds ADD COLUMN requested_by_user_id VARCHAR(64);

COMMENT ON COLUMN refunds.requested_by IS 'Nom affichable de l''auteur, ou API:<applicationId>';
COMMENT ON COLUMN refunds.requested_by_user_id IS 'sub Keycloak de l''auteur (audit), NULL pour une application';
