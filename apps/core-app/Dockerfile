# Build context MUST be the repo root:
#   docker build -f apps/core-app/Dockerfile -t <tag> .
# (core-app's settings.gradle.kts pulls in packages/event-contracts/java as a
# composite build via includeBuild, which itself composite-includes
# packages/redis-client/java — both sibling directories must be in context,
# not just the direct one, since Gradle resolves includeBuild transitively.)

# ---- Build stage ----
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

COPY packages/event-contracts/proto packages/event-contracts/proto
COPY packages/event-contracts/java  packages/event-contracts/java
COPY packages/redis-client/java     packages/redis-client/java
COPY apps/core-app                  apps/core-app

WORKDIR /workspace/apps/core-app
RUN ./gradlew :bootstrap:bootJar --no-daemon -x test

# ---- OTel javaagent stage ----
# TICKET-010 — the standard, zero-code way to instrument a JVM app (auto-
# instruments Spring MVC/JDBC). Pinned version, fetched over HTTPS from the
# project's own GitHub release assets, same "real external fetch, verified
# by actually building" discipline as every other pinned external download
# in this repo (Gradle distribution, Terraform providers, etc). 2.26.1, not
# the original 2.11.0 pin — CI's Trivy gate caught a real CRITICAL CVE
# (CVE-2026-33701) in 2.11.0, fixed in 2.26.1.
FROM eclipse-temurin:21-jre-jammy AS otel-agent
RUN curl -fsSL -o /opentelemetry-javaagent.jar \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.26.1/opentelemetry-javaagent.jar

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy AS runtime
RUN groupadd --gid 1000 nectrix \
 && useradd --uid 1000 --gid nectrix --create-home --shell /usr/sbin/nologin nectrix
WORKDIR /app
COPY --from=build /workspace/apps/core-app/bootstrap/build/libs/bootstrap.jar /app/app.jar
COPY --from=otel-agent /opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
USER nectrix
EXPOSE 8080
# OTEL_EXPORTER_OTLP_ENDPOINT/OTEL_SERVICE_NAME are read by the agent itself
# (standard OTel env vars) — defaults point nowhere real; docker-compose.yml
# overrides OTEL_EXPORTER_OTLP_ENDPOINT to http://tempo:4318. A missing/
# unreachable collector only disables export (agent logs a warning), it
# never blocks app startup.
ENV OTEL_SERVICE_NAME=core-app
ENV OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
ENV OTEL_METRICS_EXPORTER=none
ENV OTEL_LOGS_EXPORTER=none
ENTRYPOINT ["java", "-javaagent:/app/opentelemetry-javaagent.jar", "-jar", "/app/app.jar"]
