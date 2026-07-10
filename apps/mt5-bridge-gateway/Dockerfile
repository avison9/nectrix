# Build context MUST be the repo root:
#   docker build -f apps/mt5-bridge-gateway/Dockerfile -t <tag> .
# (go.work ties this module to packages/go-domain, packages/event-contracts/go,
# and packages/redis-client/go via local `use` directives — those sibling
# directories must be in context. Note: go build resolves the *whole*
# workspace's `use` list even for a module that doesn't import redis-client,
# so it must be present here too, not just in copy-engine's Dockerfile. Same
# reason apps/mt-terminal-host must be present below — a real CI failure
# ("cannot load module ../mt-terminal-host") caught this gotcha for real the
# first time that module actually landed on main.)

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

WORKDIR /workspace/apps/mt5-bridge-gateway
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/mt5-bridge-gateway .

# ---- Runtime stage ----
# distroless/static:nonroot — smaller, no shell/package-manager attack
# surface. :nonroot already runs as uid/gid 65532.
FROM gcr.io/distroless/static-debian12:nonroot AS runtime
COPY --from=build /out/mt5-bridge-gateway /usr/local/bin/mt5-bridge-gateway
EXPOSE 8092
ENTRYPOINT ["/usr/local/bin/mt5-bridge-gateway"]
