.PHONY: up down restart logs ps clean \
	devcontainer-up devcontainer-build \
	core-app-build core-app-test \
	go-build go-lint proto-gen \
	ts-install ts-build ts-lint

COMPOSE = docker compose -f docker-compose.yml -f .devcontainer/docker-compose.yml
DC_EXEC = $(COMPOSE) exec devcontainer

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

devcontainer-build: ## Build the devcontainer toolchain image (Java/Go/Node)
	$(COMPOSE) build devcontainer

devcontainer-up: ## Start infra + the devcontainer toolchain container
	$(COMPOSE) up -d

core-app-build: ## Build apps/core-app (Java/Gradle multi-module)
	$(DC_EXEC) bash -c "cd /workspace/apps/core-app && ./gradlew build"

core-app-test: ## Run apps/core-app tests, including ArchUnit module-boundary checks
	$(DC_EXEC) bash -c "cd /workspace/apps/core-app && ./gradlew test"

go-build: ## Build all Go modules (go-domain, event-contracts/go, and the three Go apps)
	# -o /dev/null: verifies compilation without writing a binary into the
	# source tree (a bare `go build ./...` on a package main writes one named
	# after the directory, right there, which then gets accidentally committed).
	$(DC_EXEC) bash -c "cd /workspace && for d in packages/go-domain packages/event-contracts/go apps/copy-engine apps/broker-adapters apps/mt5-bridge-gateway; do (cd \$$d && go build -o /dev/null ./...); done"

go-lint: ## Lint all Go modules with golangci-lint
	$(DC_EXEC) bash -c "cd /workspace && for d in packages/go-domain packages/event-contracts/go apps/copy-engine apps/broker-adapters apps/mt5-bridge-gateway; do (cd \$$d && golangci-lint run ./...); done"

proto-gen: ## Regenerate Go code from packages/event-contracts/proto
	$(DC_EXEC) bash -c "cd /workspace && protoc --proto_path=packages/event-contracts/proto --go_out=packages/event-contracts/go/gen --go_opt=paths=source_relative packages/event-contracts/proto/nectrix/events/v1/trade_event.proto"

ts-install: ## Install TS workspace dependencies (apps/web, apps/admin-portal, packages/*)
	$(DC_EXEC) bash -c "cd /workspace && npm install"

ts-build: ## Build the TS workspace via Turborepo
	$(DC_EXEC) bash -c "cd /workspace && npx turbo run build"

ts-lint: ## Lint the TS workspace via Turborepo
	$(DC_EXEC) bash -c "cd /workspace && npx turbo run lint"
