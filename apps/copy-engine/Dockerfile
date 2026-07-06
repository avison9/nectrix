# Build context MUST be the repo root:
#   docker build -f apps/copy-engine/Dockerfile -t <tag> .
# (go.work ties this module to packages/go-domain and packages/event-contracts/go
# via local `use` directives — those sibling directories must be in context.)

# ---- Build stage ----
FROM golang:1.26.4-bookworm AS build
WORKDIR /workspace

COPY go.work go.work.sum ./
COPY packages/go-domain          packages/go-domain
COPY packages/event-contracts/go packages/event-contracts/go
COPY apps/copy-engine             apps/copy-engine
COPY apps/broker-adapters         apps/broker-adapters
COPY apps/mt5-bridge-gateway      apps/mt5-bridge-gateway

WORKDIR /workspace/apps/copy-engine
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/copy-engine .

# ---- Runtime stage ----
# distroless/static:nonroot — connection-draining (AC5) is handled by an
# in-process SIGTERM handler (see main.go), not a K8s exec preStop hook, so
# no shell is needed here. :nonroot already runs as uid/gid 65532.
FROM gcr.io/distroless/static-debian12:nonroot AS runtime
COPY --from=build /out/copy-engine /usr/local/bin/copy-engine
EXPOSE 8090
ENTRYPOINT ["/usr/local/bin/copy-engine"]
