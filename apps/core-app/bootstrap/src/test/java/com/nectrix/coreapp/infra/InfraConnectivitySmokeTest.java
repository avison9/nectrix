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
 */
@Tag("integration")
class InfraConnectivitySmokeTest {

  private static final int CONNECT_TIMEOUT_MS = 5_000;

  @Test
  void postgresIsReachable() {
    assertDoesNotThrow(() -> connect("localhost", 5432));
  }

  @Test
  void redisIsReachable() {
    assertDoesNotThrow(() -> connect("localhost", 6379));
  }

  @Test
  void kafkaIsReachable() {
    assertDoesNotThrow(() -> connect("localhost", 9092));
  }

  private static void connect(String host, int port) throws IOException {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
    }
  }
}
