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
3. **MT4 is not yet supported end-to-end** — MQL4 has no native socket support, so
   `NectrixBridgeMT4.mq4`'s WebSocket-based design cannot compile as written (see finding 9 below).
   The MT4 terminal installs fine; the EA bridge does not yet exist for it. Needs a follow-up ticket
   to redesign MT4's transport before `PLATFORM=MT4` accounts can actually work.

## Container images

Two images, both built/scanned/pushed on every push to `main` via `main-pipeline.yml`'s
`build-scan-push` matrix — the same always-on path every other service in this repo uses:

- **`apps/mt-terminal-host/Dockerfile`** (the Go provisioner binary) —
  `ghcr.io/avison9/nectrix/mt-terminal-host:<commit-sha>`.
- **`apps/mt-terminal-host/terminal-image/Dockerfile`** (the Wine/terminal image) —
  `ghcr.io/avison9/nectrix/mt-terminal-image:<commit-sha>`. Previously
  **`workflow_dispatch`-only** (a standalone `build-terminal-image.yml`, now removed) while this
  `.mq5` source had never been compiled and this Dockerfile had never been built anywhere for real —
  folded into the always-on matrix now that a real CI run has proven the whole pipeline genuinely
  green end-to-end (install → compile → Trivy scan → push; see "Real, in-progress `terminal-image`
  debugging" below for the full history of what it took to get there). Meaningfully slower than
  every other leg in that matrix (~3 real minutes: Wine setup + two MetaQuotes installs + a real
  MetaEditor compile) and depends on an external MetaQuotes CDN, but `fail-fast: false` means it
  never blocks the other legs. Its on-failure diagnostics-export/upload steps (screenshots, exit
  codes, install logs) were ported into the matrix rather than dropped — this Dockerfile is
  meaningfully more fragile than everything else in it.
  `deploy-staging`/`deploy-production` patch its resolved tag into `mt-terminal-host`'s
  `TERMINAL_IMAGE` env var directly (`sed`, not `kustomize edit set image` — that only rewrites
  container `image:` fields, and this image has no Deployment of its own to rewrite one on; it's
  referenced dynamically per-broker-account by the reconciler instead).

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

**Real `terminal-image` debugging history** — now resolved and folded into `main-pipeline.yml`'s
always-on `build-scan-push` matrix (see "Container images" above); findings below are from the real
x86_64 CI runs it took to get there (originally against a standalone `build-terminal-image.yml`,
`workflow_dispatch`-only, since removed):

1. `xvfb-run` needs `xauth`, not just `xvfb` (fixed).
2. The Dockerfile has a diagnostics stage (`install-diagnostics`/`diagnostics-export`,
   `terminal-image/install-with-diagnostics.sh`) that always commits its own layer — even when the
   real `install-verified` gate fails — capturing periodic screenshots, the real exit code, and
   Wine's own stdout/stderr for both the MT5 and MT4 installs, uploaded as the
   `mt-terminal-install-diagnostics` workflow artifact on any failure. This is how (2) and (3) below
   were actually found, not guessed.
3. MetaQuotes' `/auto` flag does NOT suppress the installer's own final "Finish"/"Congratulations"
   confirmation screen — both installers genuinely complete (screenshots show the full wizard, file
   copying and all) and then just sit there. A synthetic `xdotool` Enter keypress was added to try
   dismissing it, but doesn't reliably land on the right control (may just cycle the installer's own
   promotional carousel instead).
4. Consequently, **the installer's own process exit code is not a reliable success signal under
   Wine** — confirmed exiting 1 across multiple runs even when the wizard visibly completed.
   `install-verified` gated on the real file (`metaeditor64.exe`/`metaeditor.exe` actually present),
   not the exit code, which is logged only as non-fatal context — but on the next real run, the files
   genuinely weren't there either, despite the wizard visibly completing.
5. **Root cause, found by cross-checking against a real, maintained third-party project**
   ([gmag11/MetaTrader5-Docker](https://github.com/gmag11/MetaTrader5-Docker)): this Dockerfile's
   original install command passed a custom `/portable "/S:<dir>"` destination alongside `/auto`.
   That real project's own `start.sh` uses `/auto` **alone** — no `/portable`, no custom `/S:`
   destination — and finds the installed terminal at MetaQuotes' own default location afterward
   (`$WINEPREFIX/drive_c/Program Files/MetaTrader 5/terminal64.exe`). `install-with-diagnostics.sh`
   now does the same: `/auto` alone, into its own dedicated `WINEPREFIX` per platform, then `find`s
   wherever the installer actually placed things (rather than assuming a fixed path) and copies that
   whole directory into a canonical location (`/canonical-mt5`, `/canonical-mt4`) the rest of the
   Dockerfile relies on unconditionally. The `xdotool` Enter-keypress workaround (point 3) was removed
   — unneeded once the installer isn't fighting a destination it doesn't like.
6. **Installer output casing isn't guaranteed.** A real MT5 install produced `MetaEditor64.exe`
   (capital E), not the lowercase `metaeditor64.exe` the rest of the Dockerfile assumed — Linux
   filesystems are case-sensitive, so the fixed-name check never matched even though the file was
   genuinely present. `install-with-diagnostics.sh` now explicitly re-copies the discovered exe onto
   the exact fixed-case name the Dockerfile expects, rather than relying on the installer's real
   on-disk casing.
7. **`WINEPREFIX` must be set explicitly for every wine invocation, every RUN layer.**
   `install-with-diagnostics.sh` only exports it within its own script process — that doesn't persist
   across separate Dockerfile `RUN` layers. Without it, `wine metaeditor64.exe ...` silently fell back
   to a brand-new default prefix (`/root/.wine`) that never had the real install's registry/COM setup,
   and MetaEditor failed with OLE/RPC marshalling errors. Similarly, `xvfb-run -a`'s auto-display
   selection failed near-instantly under Wine here (untested territory — every other Wine invocation
   in this Dockerfile uses a manual `Xvfb & sleep; wine; kill` pattern instead, which is what actually
   works); `compile-ea.sh` now uses that same proven pattern.
8. **The first real MetaEditor compile surfaced genuine bugs in the EA source itself** (this source
   had never been compiled by anything before this): `EventSetMillisecond` isn't a real MQL5/MQL4
   function — the correct API is `EventSetMillisecondTimer` (fixed in both `.mq5`/`.mq4`). Separately,
   `CTrade`/`#include <Trade/Trade.mqh>` failed to resolve even though the file exists on disk — a
   silent `/auto` install only places the terminal/editor executables, not the MQL5/MQL4 Standard
   Library (`Include/`), which is populated by the terminal's own first-run initialization instead.
   `install-with-diagnostics.sh` now launches the discovered terminal once (`/portable`, ~15s, then
   killed) right after install to trigger that initialization before copying files into the canonical
   directory. **`NectrixBridgeMT5.mq5` now compiles with 0 errors, 0 warnings — the first real proof
   this source is correct**, confirmed via a real CI run.
9. **MT4 EA compilation is deliberately disabled — a genuine platform gap, not a bug.** MQL4 has no
   native `Socket*()` functions at all (confirmed against MQL4's own reference docs — `Socket*` is an
   MQL5-only addition), so `NectrixBridgeMT4.mq4`'s WebSocket-based design as written cannot compile
   on real MT4, full stop. The MT4 terminal itself still installs fine (`install-verified` passes for
   it); only EA compilation and attachment are blocked. `compile-ea` no longer attempts to compile the
   `.mq4` source, `entrypoint.sh` fails fast and clearly for `PLATFORM=MT4` rather than silently
   launching a terminal with no EA able to attach. **Follow-up ticket needed** to redesign MT4's
   transport — most likely either a bundled native DLL for raw sockets (keeps the same WebSocket
   protocol/design symmetric with MT5, but adds a new native-code component and a real security
   surface, since DLL imports must be allowed) or HTTP long-polling via MQL4's native `WebRequest()`
   (stays pure MQL, no DLL, but needs new `apps/mt5-bridge-gateway` work to support polling alongside
   the existing WebSocket protocol). Not designed here — deliberately deferred so MT5 could ship.
