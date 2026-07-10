# mt-terminal-host

Nectrix-hosted MT5/MT4 terminals — so linking an MT5/MT4 account is "enter credentials, done,"
without the user installing or attaching anything themselves. This is a real, additional capability
layered on top of TICKET-102's EA-bridge design (`apps/mt5-bridge-gateway`); it does not change that
service's gateway/EA-bridge protocol at all — a Nectrix-hosted terminal is just another EA client
dialing the same, already-built, already-tested gateway.

`apps/mt5-bridge-gateway`'s own design assumed the *user* runs their own MT5/MT4 terminal and
manually pastes a pairing token into the EA's input parameters. That's real, but not the UX the
platform wants. This service is a Kubernetes-native controller: for every linked MT5/MT4
`broker_accounts` row, it provisions a real Wine-hosted terminal (with the EA pre-attached and
pre-configured) running as its own Kubernetes Deployment — the user never touches MetaTrader at all.

## Architecture

```
User submits real credentials
        │
        ▼
Core App: MtLinkingService (unmodified) — stores {login,password,server,pairingToken}
        │  encrypted, row PENDING
        ▼
mt-terminal-host — reconcile loop, polls Core App, one per MT5/MT4 account:
        │  ensures a K8s Deployment+Secret exists, labeled nectrix.io/broker-account-id=<id>
        ▼
Terminal pod (terminal-image/Dockerfile: Wine + Xvfb + real MT5/MT4 terminal + our compiled EA)
        │  entrypoint.sh generates .ini (auto-login) + .set (EA inputs: pairingToken, gatewayUrl)
        │  launches xvfb-run wine terminal64.exe /portable /config:...
        ▼
EA (apps/mt5-bridge-gateway/ea/{mt5,mt4}, unchanged) dials apps/mt5-bridge-gateway — exactly
like a user-run terminal would
```

## Packages (`internal/`)

- **`coreappclient`** — HTTP client for Core App's internal endpoints. Authenticates with **two
  separate secrets**: the shared `X-Internal-Service-Token` (for the general broker-accounts
  listing, the same unmodified endpoint `apps/mt5-bridge-gateway`'s `internal/pairing` already
  polls) and a second, narrowly-scoped `MT_TERMINAL_PROVISIONER_TOKEN` (for the new
  `mt-terminal-credentials` endpoint — the one place a real plaintext broker password is ever
  returned over the wire). See `apps/core-app/README.md`'s own section on why these are
  deliberately separate.
- **`k8sprovision`** — the first use of `k8s.io/client-go` in this monorepo. `EnsureTerminal`
  server-side-applies a per-account `Secret` (login/password/server/pairingToken/gatewayHost/
  gatewayPort/platform) and a single-replica `Deployment`, both labeled
  `nectrix.io/broker-account-id`; `TeardownTerminal` deletes both. `ListProvisionedAccountIDs`
  reads the real, current state straight from the cluster — Kubernetes itself is the source of
  truth for "which accounts have a running terminal," not an in-memory map (a provisioner restart
  never drifts from real cluster state this way).
- **`reconcile`** — the poll → reconcile loop (`main.go`'s `reconcileInterval`, 30s): lists MT5/MT4
  accounts Core App wants a terminal for, ensures one exists for each (fetching real credentials —
  and thus decrypting a real password — only once per newly-discovered account, not every cycle),
  and tears down terminals for accounts no longer listed.

## Design references

- `apps/mt5-bridge-gateway/README.md` — the EA-bridge gateway/protocol this service's terminals dial into (unmodified).
- `apps/core-app/README.md`'s MT5/MT4 sections — the Java linking flow and the new `mt-terminal-credentials` endpoint.
- `nectrix_plan/docs/07-auth-onboarding-broker-linking.md` §7.7 — MT5/MT4 linking strategy decision framework.

## Dependencies

- `packages/go-domain` — shared normalized domain types (not directly used by this service's own
  logic today, but keeps this module a real citizen of the shared `go.work` for future
  `domain.BrokerAdapter`-shaped integration).
- `k8s.io/client-go`, `k8s.io/api`, `k8s.io/apimachinery` — real Kubernetes API access.

## Configuration

Real env vars this service reads (see root `.env.example`):

| Var | Required | Notes |
|---|---|---|
| `INTERNAL_SERVICE_TOKEN` | yes | Shared secret for the general broker-accounts listing — must match Core App's own copy. |
| `MT_TERMINAL_PROVISIONER_TOKEN` | yes | Separate secret for the real-password `mt-terminal-credentials` fetch — must match Core App's own copy. **Never the same value as `INTERNAL_SERVICE_TOKEN`.** |
| `TERMINAL_IMAGE` | yes | The `terminal-image` reference (see below) each provisioned Deployment runs. |
| `TERMINAL_NAMESPACE` | no (default `mt-terminals`) | Where terminal Deployments/Secrets are provisioned — must match the RBAC Role's own namespace (`deploy/base/mt-terminal-host/role.yaml`). |
| `GATEWAY_HOST`/`GATEWAY_PORT` | no (default `mt5-bridge-gateway.copy-engine.svc.cluster.local`/`8092`) | Baked into every provisioned terminal's Secret — what the EA is told to dial. |
| `CORE_APP_INTERNAL_BASE_URL` | no (default `http://localhost:8080`) | Where `coreappclient` calls Core App's internal API. |

## RBAC

`deploy/base/mt-terminal-host/{serviceaccount,role,rolebinding}.yaml` — a namespace-scoped `Role`
(not `ClusterRole`) living in `mt-terminals`, granting the `copy-engine`-namespaced
`mt-terminal-host` ServiceAccount `get/list/watch/create/update/patch/delete` on `deployments` and
`secrets` **in `mt-terminals` only** — nothing cluster-scoped, nothing in any other namespace.
Verified for real (see "Live verification" below), including a negative case proving the same
identity is genuinely `Forbidden` outside that one namespace.

## The terminal image (`terminal-image/`)

`terminal-image/Dockerfile` — a real, multi-stage Wine + Xvfb image: installs the official generic
MetaTrader5/MetaTrader4 terminals (each of which bundles MetaEditor), compiles
`apps/mt5-bridge-gateway/ea/{mt5,mt4}/*.mq5,*.mq4` **inside the build itself** (no separate compile
step/artifact hand-off needed), and packages the resulting `.ex5`/`.ex4` into the final image.
`terminal-image/entrypoint.sh` reads the per-account Secret's env vars, generates a real MT5/MT4
`/config` `.ini` (headless auto-login: `AllowLiveTrading=1` bypasses the manual "allow live trading"
GUI confirmation) and `.set` (EA input parameters: `InpPairingToken`/`InpGatewayHost`/
`InpGatewayPort` — must match the EA source's own `input` declarations exactly), then launches the
real terminal under Xvfb.

**Two real, open, unresolved items** (see the Dockerfile's own header):

1. **MetaQuotes' terminal EULA/licensing** for redistributing the terminal binary inside a container
   image Nectrix hosts itself — genuinely unvalidated, must be checked before this image is ever
   built for real.
2. **Network egress** for `mt-terminals` pods — they hold live plaintext broker credentials and
   currently have no NetworkPolicy-enforced restriction on which external broker-server hosts/ports
   they may reach (K8s NetworkPolicy is IP/CIDR-based, not hostname-based, and valid destinations
   are dynamic per-broker). A real, documented gap, not silently ignored.

## Container images

Two separate images, two separate CI paths:

- **`apps/mt-terminal-host/Dockerfile`** (the Go provisioner binary) — built/scanned/pushed on
  every push to `main`, exactly like every other Go service in this repo
  (`ghcr.io/avison9/nectrix/mt-terminal-host:<commit-sha>`).
- **`apps/mt-terminal-host/terminal-image/Dockerfile`** (the Wine/terminal image) — **manual
  (`workflow_dispatch`) only**, not on every push. This `.mq5`/`.mq4` source has never been compiled
  anywhere, this Dockerfile has never been built anywhere (no Wine in any environment this ticket
  had access to), and the licensing question above is still open — wiring it into the always-on
  pipeline before any of that is resolved would make routine, unrelated commits fail the whole
  pipeline on a genuinely experimental path. Run by hand (Actions tab → Run workflow) once ready to
  actually validate this for real.

## Commands

```
make go-build   # builds all Go modules, including this one
make go-test    # tests all Go modules, including this one (unit only)
make go-lint    # golangci-lint across all Go modules
```

Real, live-cluster RBAC + reconcile verification (host-level `kind`/`kubectl` — see
`infra/kind/README.md`'s "Host-level tools" note):

```
make kind-mt-terminal-host-test
```

Run directly: `go run .` (listens on `:8093`) — needs the env vars above set, and a reachable
Kubernetes API (in-cluster config only — see `main.go`).

## Live verification

**What's proven automatically, no real terminal or even a real cluster needed** (`internal/reconcile`,
`internal/k8sprovision` test suites — `make go-test`): the reconcile logic (new-account discovery,
credential-fetch gating, idempotent re-provisioning, teardown-on-removal) against fakes, and
`EnsureTerminal`/`TeardownTerminal`/`ListProvisionedAccountIDs` against a real `client-go` fake
clientset (real object creation/labeling/deletion assertions, not a hand-rolled mock).

**What's proven against a real Kubernetes API server** (`make kind-mt-terminal-host-test` —
`infra/kind/mt-terminal-host-test/run.sh`): the exact RBAC manifests under `deploy/base/
mt-terminal-host/` (not a test-local copy), impersonating the real `system:serviceaccount:
copy-engine:mt-terminal-host` identity — proves that identity can really create/delete
`Secret`/`Deployment` objects in `mt-terminals`, and is really `Forbidden` from doing the same in
`copy-engine`. Run live against a real local `kind` cluster and confirmed passing (both the positive
grant and the negative/forbidden case) as part of building this service.

**Not verifiable in this environment, flagged honestly** (same discipline as
`apps/mt5-bridge-gateway`'s own EA source): an actual `terminal-image` container booting, compiling
the EA for real, logging into a real broker, and the EA dialing the gateway end-to-end. No Wine
exists in this devcontainer, and there's no single environment here with both a Go toolchain and
`kind`/`kubectl` together (the real Go+kind integration test,
`internal/reconcile/reconcile_integration_test.go`, `//go:build integration`, exists and compiles
cleanly for a CI environment that has both — just not exercised here). This will need its own
runbook once the terminal image has been built and validated for real.
