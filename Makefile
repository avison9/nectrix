.PHONY: up down restart logs ps clean

up: ## Start infra services (Postgres, Redis, Kafka, Kafka UI, MinIO)
	docker compose up -d
	docker compose ps

down: ## Stop infra services
	docker compose down

restart: down up ## Restart infra services

logs: ## Tail logs for all infra services
	docker compose logs -f

ps: ## Show infra service status
	docker compose ps

clean: ## Stop infra services and delete all local data volumes
	docker compose down -v
