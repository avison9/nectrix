# Build context MUST be the repo root:
#   docker build -f apps/mt-terminal-host/Dockerfile -t <tag> .
# (go.work ties this module to packages/go-domain via a local `use`
# directive — that sibling directory must be in context. Note: go build
# resolves the *whole* workspace's `use` list even for a module that
# doesn't import every sibling package, so packages/event-contracts/go,
# packages/redis-client/go, and packages/ctrader-proto/go must be present
# here too, not just in copy-engine's/broker-adapters'/mt5-bridge-gateway's
# Dockerfiles — same gotcha those three already document.)
#
# This is the mt-terminal-host PROVISIONER binary's own image — the
# Kubernetes-reconciling Go process. The Wine/Xvfb/MetaTrader-terminal image
# it provisions Deployments FROM is a completely separate artifact: see
# apps/mt-terminal-host/terminal-image/Dockerfile.

# ---- Build stage ----
FROM golang:1.26.4-bookworm AS build
WORKDIR /workspace

COPY go.work go.work.sum ./
COPY packages/go-domain          packages/go-domain
COPY packages/event-contracts/go packages/event-contracts/go
COPY packages/redis-client/go    packages/redis-client/go
COPY packages/ctrader-proto/go   packages/ctrader-proto/go
COPY apps/copy-engine             apps/copy-engine
COPY apps/broker-adapters         apps/broker-adapters
COPY apps/mt5-bridge-gateway      apps/mt5-bridge-gateway
COPY apps/mt-terminal-host        apps/mt-terminal-host

WORKDIR /workspace/apps/mt-terminal-host
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/mt-terminal-host .

# ---- Runtime stage ----
# distroless/static:nonroot — smaller, no shell/package-manager attack
# surface. :nonroot already runs as uid/gid 65532.
FROM gcr.io/distroless/static-debian12:nonroot AS runtime
COPY --from=build /out/mt-terminal-host /usr/local/bin/mt-terminal-host
EXPOSE 8093
ENTRYPOINT ["/usr/local/bin/mt-terminal-host"]
