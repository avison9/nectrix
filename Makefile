.PHONY: up down restart logs ps clean \
	devcontainer-up devcontainer-build \
	core-app-build core-app-test \
	go-build go-test go-lint proto-gen \
	ts-install ts-build ts-lint \
	tf-fmt tf-validate tf-lint tf-checkov \
	kind-netpol-test kind-hpa-test \
	db-migrate db-migrate-down db-seed-dev db-status \
	role-grant role-revoke role-list \
	kafka-topics \
	observability-verify \
	localstack-init

# Count of the core (non-dev-context) changesets — update when adding new
# ones. Used for a full rollback (db-migrate-down). If dev seed data
# (db-seed-dev) has also been applied, roll that back first — this count
# only unwinds the 42 core changesets, not the additional dev-context ones.
DB_CHANGESET_COUNT = 42

TF_DIRS = infra/terraform/aws infra/terraform/gcp

COMPOSE = docker compose -f docker-compose.yml -f .devcontainer/docker-compose.yml
DC_EXEC = $(COMPOSE) exec devcontainer

up: ## Start infra services (Postgres, Redis, Kafka, Kafka UI, MinIO, observability stack)
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

go-build: ## Build all Go modules (go-domain, event-contracts/go, redis-client/go, and the three Go apps)
	# -o /dev/null: verifies compilation without writing a binary into the
	# source tree (a bare `go build ./...` on a package main writes one named
	# after the directory, right there, which then gets accidentally committed).
	$(DC_EXEC) bash -c "cd /workspace && for d in packages/go-domain packages/event-contracts/go packages/redis-client/go apps/copy-engine apps/broker-adapters apps/mt5-bridge-gateway; do (cd \$$d && go build -o /dev/null ./...); done"

go-test: ## Test all Go modules (excludes //go:build integration-tagged tests)
	$(DC_EXEC) bash -c "cd /workspace && for d in packages/go-domain packages/event-contracts/go packages/redis-client/go apps/copy-engine apps/broker-adapters apps/mt5-bridge-gateway; do (cd \$$d && go test ./...); done"

go-lint: ## Lint all Go modules with golangci-lint
	$(DC_EXEC) bash -c "cd /workspace && for d in packages/go-domain packages/event-contracts/go packages/redis-client/go apps/copy-engine apps/broker-adapters apps/mt5-bridge-gateway; do (cd \$$d && golangci-lint run ./...); done"

proto-gen: ## Regenerate Go code from packages/event-contracts/proto (all .proto files, not just one)
	$(DC_EXEC) bash -c "cd /workspace && protoc --proto_path=packages/event-contracts/proto --go_out=packages/event-contracts/go/gen --go_opt=paths=source_relative packages/event-contracts/proto/nectrix/events/v1/*.proto"

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

# --- TICKET-006: role management CLI (AC4 — "visible/manageable via an
# internal admin utility, can be a CLI script at this phase"). Plain psql
# against roles/user_roles, connecting as the same nectrix_app role the
# running app itself uses (least privilege — no new grants needed, and
# consistent with db-migrate's "always use the credentials the real
# consumer would use" spirit, just for the app role instead of the
# superuser). No new Java code: a full admin-portal role-management UI is
# TICKET-012's job, not this ticket's.

role-grant: ## Grant a role: make role-grant EMAIL=foo@example.com ROLE=ADMIN
	@test -n "$(EMAIL)" || { echo "EMAIL is required, e.g. make role-grant EMAIL=foo@example.com ROLE=ADMIN"; exit 1; }
	@test -n "$(ROLE)" || { echo "ROLE is required, e.g. make role-grant EMAIL=foo@example.com ROLE=ADMIN"; exit 1; }
	$(DC_EXEC) bash -c "psql \"postgresql://nectrix_app:\$$POSTGRES_APP_PASSWORD@\$$POSTGRES_HOST:\$$POSTGRES_PORT/\$$POSTGRES_DB\" -v ON_ERROR_STOP=1 -c \"INSERT INTO user_roles (user_id, role_id) SELECT u.id, r.id FROM users u, roles r WHERE u.email = '$(EMAIL)' AND r.name = '$(ROLE)' ON CONFLICT (user_id, role_id) DO NOTHING;\""

role-revoke: ## Revoke a role: make role-revoke EMAIL=foo@example.com ROLE=ADMIN
	@test -n "$(EMAIL)" || { echo "EMAIL is required, e.g. make role-revoke EMAIL=foo@example.com ROLE=ADMIN"; exit 1; }
	@test -n "$(ROLE)" || { echo "ROLE is required, e.g. make role-revoke EMAIL=foo@example.com ROLE=ADMIN"; exit 1; }
	$(DC_EXEC) bash -c "psql \"postgresql://nectrix_app:\$$POSTGRES_APP_PASSWORD@\$$POSTGRES_HOST:\$$POSTGRES_PORT/\$$POSTGRES_DB\" -v ON_ERROR_STOP=1 -c \"DELETE FROM user_roles USING users u, roles r WHERE user_roles.user_id = u.id AND user_roles.role_id = r.id AND u.email = '$(EMAIL)' AND r.name = '$(ROLE)';\""

role-list: ## List roles for a user: make role-list EMAIL=foo@example.com
	@test -n "$(EMAIL)" || { echo "EMAIL is required, e.g. make role-list EMAIL=foo@example.com"; exit 1; }
	$(DC_EXEC) bash -c "psql \"postgresql://nectrix_app:\$$POSTGRES_APP_PASSWORD@\$$POSTGRES_HOST:\$$POSTGRES_PORT/\$$POSTGRES_DB\" -v ON_ERROR_STOP=1 -c \"SELECT r.name FROM roles r JOIN user_roles ur ON ur.role_id = r.id JOIN users u ON u.id = ur.user_id WHERE u.email = '$(EMAIL)';\""

# --- TICKET-007: explicit Kafka topic catalog (see infra/kafka/create-topics.sh
# for the full topic list/partitioning rationale). Plain `docker compose exec`
# against the kafka service itself, not $(DC_EXEC)/the devcontainer — same
# precedent as the `up`/`down`/`ps` targets above, since this needs to reach
# the broker container directly, not run tooling from inside the devcontainer.

kafka-topics: ## Create the full topic catalog (idempotent) against the local/CI broker
	./infra/kafka/create-topics.sh

localstack-init: ## Create the version-1 envelope-encryption KMS key in LocalStack (idempotent)
	./infra/localstack/init-kms.sh

observability-verify: ## Real, hands-on proof of TICKET-010's AC1-4 against the docker-compose observability stack
	./infra/observability/verify.sh
