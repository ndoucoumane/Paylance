# Paylance — commandes de développement local
# Copier .env.example -> .env avant utilisation.
#
# Note : .env n'est PAS chargé via `include` (GNU Make interprète lui-même les `$`,
# ce qui casserait les hashs BCrypt du type $2a$12$...). Les cibles qui ont besoin
# des variables (run, dev, migrate) les sourcent via le shell à l'exécution.

.DEFAULT_GOAL := help

.PHONY: help dev db build run test migrate stop down logs clean keys

help: ## Affiche cette aide
	@echo "Paylance — commandes disponibles :"
	@grep -hE '^[a-zA-Z_-]+:.*## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

dev: ## Démarre tout l'environnement local (DB + Redis, build, run)
	@echo "Démarrage de l'environnement local Paylance..."
	docker compose up -d
	@echo "Attente de la base de données..."
	sleep 5
	mvn clean install -DskipTests
	set -a && . ./.env && set +a && mvn spring-boot:run

db: ## Démarre uniquement Postgres + Redis + Adminer
	docker compose up -d

build: ## Compile le projet (sans les tests)
	mvn clean install -DskipTests

run: ## Lance l'application (nécessite `make db` au préalable)
	set -a && . ./.env && set +a && mvn spring-boot:run

test: ## Lance les tests unitaires
	mvn test

migrate: ## Applique les migrations Flyway sans démarrer l'application
	set -a && . ./.env && set +a && mvn flyway:info flyway:migrate

keys: ## Génère une clé API plateforme (clé en clair + hash BCrypt)
	mvn -q compile exec:java -Dexec.mainClass=com.paylance.tools.ApiKeyGeneratorCli

logs: ## Affiche les logs des conteneurs (Postgres, Redis, Adminer)
	docker compose logs -f

stop: ## Arrête les conteneurs (données conservées)
	docker compose stop

down: ## Arrête et supprime les conteneurs + volumes (⚠️ efface les données locales)
	docker compose down -v

clean: ## Nettoie les artefacts de build Maven
	mvn clean
