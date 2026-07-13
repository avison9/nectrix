# Build context MUST be this directory:
#   docker build -f infra/observability/webhook-catcher/Dockerfile -t <tag> infra/observability/webhook-catcher
# Standalone module (stdlib only, not part of the repo's go.work workspace)
# — this is local dev/CI test tooling, not a deployable app.

FROM golang:1.26.5-bookworm AS build
WORKDIR /workspace
COPY go.mod main.go ./
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/webhook-catcher .

FROM gcr.io/distroless/static-debian12:nonroot AS runtime
COPY --from=build /out/webhook-catcher /usr/local/bin/webhook-catcher
EXPOSE 9099
ENTRYPOINT ["/usr/local/bin/webhook-catcher"]
