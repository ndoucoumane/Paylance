-- Les URLs de redirection ne sont plus globales : elles viennent de la requête ou de l'application.
ALTER TABLE payments ADD COLUMN success_url VARCHAR(500);
ALTER TABLE payments ADD COLUMN error_url VARCHAR(500);
