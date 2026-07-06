# Build context MUST be the repo root:
#   docker build -f apps/core-app/Dockerfile -t <tag> .
# (core-app's settings.gradle.kts pulls in packages/event-contracts/java as a
# composite build via includeBuild — that sibling directory must be in context.)

# ---- Build stage ----
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

COPY packages/event-contracts/proto packages/event-contracts/proto
COPY packages/event-contracts/java  packages/event-contracts/java
COPY apps/core-app                  apps/core-app

WORKDIR /workspace/apps/core-app
RUN ./gradlew :bootstrap:bootJar --no-daemon -x test

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy AS runtime
RUN groupadd --gid 1000 nectrix \
 && useradd --uid 1000 --gid nectrix --create-home --shell /usr/sbin/nologin nectrix
WORKDIR /app
COPY --from=build /workspace/apps/core-app/bootstrap/build/libs/bootstrap.jar /app/app.jar
USER nectrix
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
