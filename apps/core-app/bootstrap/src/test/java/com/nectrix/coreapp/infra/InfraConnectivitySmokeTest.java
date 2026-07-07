package com.nectrix.coreapp.infra;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Proves the CI integration-test stage's ephemeral Postgres/Redis/Kafka (started via {@code docker
 * compose up -d --wait}) are actually reachable. Deliberately stdlib-only — no JDBC driver, Jedis,
 * or Kafka client dependency exists yet (those land with TICKET-004/007/008), so this only asserts
 * TCP reachability, not protocol-level correctness.
 *
 * <p>Excluded from the normal {@code test}/{@code build} lifecycle (see {@code
 * bootstrap/build.gradle.kts}'s {@code excludeTags("integration")}) since no infra is running
 * during a plain {@code ./gradlew build}.
 *
 * <p>Host/port are env-driven, not hardcoded to {@code localhost} — same {@code *_HOST}/{@code
 * *_PORT} convention as {@code application.yml} and {@code SchemaConstraintsIntegrationTest}. CI's
 * bare runner publishes ports directly to its own {@code localhost} (the defaults below), but from
 * inside the devcontainer, Postgres/Redis/Kafka are separate containers on the compose network,
 * reachable only by service name — {@code .devcontainer/docker-compose.yml} sets {@code
 * POSTGRES_HOST=postgres}, {@code REDIS_HOST=redis}, {@code KAFKA_HOST=kafka} accordingly. Kafka
 * specifically needs its *internal* listener port (29092, {@code KAFKA_ADVERTISED_LISTENERS}'
 * {@code PLAINTEXT://kafka:29092} in {@code docker-compose.yml}), not the 9092 EXTERNAL listener —
 * that one is advertised as {@code localhost:9092}, which only resolves correctly for a client
 * connecting from the host, not from another container on the same network.
 */
@Tag("integration")
class InfraConnectivitySmokeTest {

  private static final int CONNECT_TIMEOUT_MS = 5_000;

  @Test
  void postgresIsReachable() {
    assertDoesNotThrow(
        () -> connect(envOr("POSTGRES_HOST", "localhost"), envOrInt("POSTGRES_PORT", 5432)));
  }

  @Test
  void redisIsReachable() {
    assertDoesNotThrow(
        () -> connect(envOr("REDIS_HOST", "localhost"), envOrInt("REDIS_PORT", 6379)));
  }

  @Test
  void kafkaIsReachable() {
    assertDoesNotThrow(
        () -> connect(envOr("KAFKA_HOST", "localhost"), envOrInt("KAFKA_PORT", 9092)));
  }

  private static String envOr(String key, String fallback) {
    String value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : value;
  }

  private static int envOrInt(String key, int fallback) {
    String value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
  }

  private static void connect(String host, int port) throws IOException {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
    }
  }
}
