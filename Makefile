.PHONY: up down restart logs ps clean \
	devcontainer-up devcontainer-build \
	core-app-build core-app-test \
	go-build go-test go-lint proto-gen \
	ts-install ts-build ts-lint \
	tf-fmt tf-validate tf-lint tf-checkov \
	kind-netpol-test kind-hpa-test \
	db-migrate db-migrate-down db-seed-dev db-status

# Count of the core (non-dev-context) changesets — update when adding new
# ones. Used for a full rollback (db-migrate-down). If dev seed data
# (db-seed-dev) has also been applied, roll that back first — this count
# only unwinds the 40 core changesets, not the additional dev-context ones.
DB_CHANGESET_COUNT = 40

TF_DIRS = infra/terraform/aws infra/terraform/gcp

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

go-test: ## Test all Go modules (excludes //go:build integration-tagged tests)
	$(DC_EXEC) bash -c "cd /workspace && for d in packages/go-domain packages/event-contracts/go apps/copy-engine apps/broker-adapters apps/mt5-bridge-gateway; do (cd \$$d && go test ./...); done"

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

# --- infra/terraform: host-level tools (terraform, tflint, checkov), same
# precedent as kind/kubectl/kustomize below — see infra/terraform/README.md.
# Fully offline: no cloud credentials are read or required by any of these.

tf-fmt: ## Check Terraform formatting for both clouds
	terraform fmt -check -recursive infra/terraform

tf-validate: ## terraform init -backend=false + validate for both clouds (no credentials)
	for d in $(TF_DIRS); do (cd $$d && terraform init -backend=false -input=false && terraform validate); done

tf-lint: ## tflint for both clouds (deep_check stays off — see .tflint.hcl)
	for d in $(TF_DIRS); do (cd $$d && tflint --init && tflint); done

tf-checkov: ## Static analysis (checkov) across both cloud dirs
	checkov -d infra/terraform --config-file infra/terraform/.checkov.yaml

# --- infra/kind: local hands-on verification harnesses (AC3/AC4) — host-level
# tools (kind, kubectl), see infra/kind/README.md.

kind-netpol-test: ## Real kind+Calico run proving the staging/production network-policy shape (AC3)
	./infra/kind/netpol-test/run.sh

kind-hpa-test: ## Real kind+metrics-server run proving HPA wiring (AC4)
	./infra/kind/hpa-test/run.sh

# --- apps/core-app/db: Liquibase migrations (TICKET-004) — always run as the
# nectrix superuser, never by the running app (see apps/core-app/db/README.md).

db-migrate: ## Run all pending Liquibase changesets (schema + reference-data)
	$(DC_EXEC) bash -c "cd /workspace/apps/core-app && ./gradlew :db:update"

db-migrate-down: ## Roll back every changeset (real Liquibase rollback, not clean+reapply)
	$(DC_EXEC) bash -c "cd /workspace/apps/core-app && ./gradlew :db:rollbackCount -PliquibaseCount=$(DB_CHANGESET_COUNT)"

db-seed-dev: ## Apply dev-only seed data on top (context=dev — never staging/production)
	$(DC_EXEC) bash -c "cd /workspace/apps/core-app && ./gradlew :db:update -PliquibaseContexts=dev"

db-status: ## List pending Liquibase changesets
	$(DC_EXEC) bash -c "cd /workspace/apps/core-app && ./gradlew :db:status"
