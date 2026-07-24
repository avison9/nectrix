package com.nectrix.coreapp.admin.client;

import com.nectrix.coreapp.admin.config.AdminProperties;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Engine Control page — calls broker-adapters/copy-engine/mt5-bridge-gateway's own {@code GET
 * /internal/self/status} (see each service's {@code internalapi}/{@code httpapi} package for the
 * exact JSON shape). Mirrors {@link MtTerminalHostClient}'s shape exactly: same {@code RestClient},
 * same {@code X-Internal-Service-Token} header, same {@link Optional#empty()}-on-unreachable
 * convention — {@link Optional#empty()} means the service itself couldn't be reached at all (a
 * real, distinct failure mode AdminController surfaces as DISCONNECTED), never conflated with a
 * reachable-but-idle response.
 *
 * <p>copy-engine's own self-status response uses {@code activeRelationshipCount} where
 * broker-adapters/mt5-bridge-gateway use {@code connectedCount} (see each service's own doc
 * comment on why "connected count" means something different for a Kafka-consuming pipeline than
 * for a broker-connection-holding adapter) — both are normalized into this client's single {@link
 * EngineSelfStatus#count()} field, since AdminController/the Engine Control page only ever need
 * "how many things is this engine actively servicing," not the field name that produced it.
 */
@Service
public class InfraStatusClient {

  private final RestClient restClient = RestClient.create();
  private final AdminProperties.EngineService brokerAdapters;
  private final AdminProperties.EngineService copyEngine;
  private final AdminProperties.EngineService mtBridge;

  public InfraStatusClient(AdminProperties props) {
    this.brokerAdapters = props.brokerAdapters();
    this.copyEngine = props.copyEngine();
    this.mtBridge = props.mtBridge();
  }

  public Optional<EngineSelfStatus> getBrokerAdaptersStatus() {
    return fetchConnectedCountShape(brokerAdapters);
  }

  public Optional<EngineSelfStatus> getMt5BridgeGatewayStatus() {
    return fetchConnectedCountShape(mtBridge);
  }

  public Optional<EngineSelfStatus> getCopyEngineStatus() {
    try {
      CopyEngineStatusWire wire =
          restClient
              .get()
              .uri(copyEngine.internalBaseUrl() + "/internal/self/status")
              .header("X-Internal-Service-Token", copyEngine.serviceToken())
              .retrieve()
              .body(CopyEngineStatusWire.class);
      if (wire == null) {
        return Optional.empty();
      }
      return Optional.of(new EngineSelfStatus(wire.activeRelationshipCount(), wire.lastReconcileAt()));
    } catch (RestClientException e) {
      return Optional.empty();
    }
  }

  private Optional<EngineSelfStatus> fetchConnectedCountShape(AdminProperties.EngineService config) {
    try {
      ConnectedCountStatusWire wire =
          restClient
              .get()
              .uri(config.internalBaseUrl() + "/internal/self/status")
              .header("X-Internal-Service-Token", config.serviceToken())
              .retrieve()
              .body(ConnectedCountStatusWire.class);
      if (wire == null) {
        return Optional.empty();
      }
      return Optional.of(new EngineSelfStatus(wire.connectedCount(), wire.lastReconcileAt()));
    } catch (RestClientException e) {
      return Optional.empty();
    }
  }

  /** Mirrors apps/broker-adapters'/apps/mt5-bridge-gateway's identical selfStatusResponse shape. */
  private record ConnectedCountStatusWire(int connectedCount, Instant lastReconcileAt) {}

  /** Mirrors apps/copy-engine's own selfStatusResponse shape. */
  private record CopyEngineStatusWire(int activeRelationshipCount, Instant lastReconcileAt) {}

  /** Normalized shape AdminController/the Engine Control page actually consume. */
  public record EngineSelfStatus(int count, Instant lastReconcileAt) {}
}
