package com.nectrix.coreapp.invitations.service;

import com.nectrix.coreapp.crypto.api.EnvelopeEncryptionService;
import com.nectrix.coreapp.invitations.repository.BrokerAccountRepository;
import com.nectrix.events.consumer.EventProducer;
import com.nectrix.events.v1.BrokerConnectionEvent;
import com.nectrix.events.v1.BrokerConnectionEventType;
import com.nectrix.events.v1.EventEnvelope;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * TICKET-101 — the three internal-only endpoints apps/broker-adapters (Go) calls: a lightweight
 * listing (for its reconciliation poll loop), decrypted credentials (fetched once per newly-
 * discovered account), and connection-status reporting (health-check-driven transitions, which also
 * publishes {@link BrokerConnectionEvent} — this platform's first real Java business Kafka
 * producer).
 */
@Service
public class BrokerAccountInternalService {

  private final BrokerAccountRepository repository;
  private final EnvelopeEncryptionService envelopeEncryptionService;
  private final ObjectMapper objectMapper;
  private final EventProducer<BrokerConnectionEvent> brokerConnectionEventProducer;

  public BrokerAccountInternalService(
      BrokerAccountRepository repository,
      EnvelopeEncryptionService envelopeEncryptionService,
      ObjectMapper objectMapper,
      EventProducer<BrokerConnectionEvent> brokerConnectionEventProducer) {
    this.repository = repository;
    this.envelopeEncryptionService = envelopeEncryptionService;
    this.objectMapper = objectMapper;
    this.brokerConnectionEventProducer = brokerConnectionEventProducer;
  }

  public List<BrokerAccountRepository.AccountRef> listAccounts(
      List<String> statuses, String brokerType) {
    return repository.findByStatusAndBrokerType(statuses, brokerType);
  }

  public DecryptedCredentials fetchCredentials(UUID brokerAccountId) {
    BrokerAccountRepository.CredentialsRow row =
        repository
            .findCredentialsById(brokerAccountId)
            .orElseThrow(BrokerAccountNotFoundException::new);
    String ciphertext = new String(row.credentialsCiphertext(), StandardCharsets.UTF_8);
    String json = envelopeEncryptionService.decryptField(ciphertext, row.credentialsKeyVersion());
    BrokerLinkingService.StoredCredentials credentials =
        objectMapper.readValue(json, BrokerLinkingService.StoredCredentials.class);
    return new DecryptedCredentials(
        credentials.accessToken(),
        credentials.refreshToken(),
        Long.parseLong(row.brokerAccountLogin()),
        !row.isDemo());
  }

  /**
   * TICKET-102 — the MT5/MT4 counterpart of {@link #fetchCredentials}, called by
   * apps/mt5-bridge-gateway's internal/pairing discovery loop. Deliberately never returns {@code
   * password}: the gateway only needs {@code login}/{@code server} (to cross-check against what a
   * connecting EA session reports) and {@code pairingToken} (to attribute the session) — the
   * terminal password is never transmitted anywhere past this decrypt, since the EA authenticates
   * to its own terminal locally, not to this platform.
   */
  public DecryptedMtCredentials fetchMtCredentials(UUID brokerAccountId) {
    BrokerAccountRepository.CredentialsRow row =
        repository
            .findCredentialsById(brokerAccountId)
            .orElseThrow(BrokerAccountNotFoundException::new);
    String ciphertext = new String(row.credentialsCiphertext(), StandardCharsets.UTF_8);
    String json = envelopeEncryptionService.decryptField(ciphertext, row.credentialsKeyVersion());
    MtLinkingService.MtStoredCredentials credentials =
        objectMapper.readValue(json, MtLinkingService.MtStoredCredentials.class);
    return new DecryptedMtCredentials(
        credentials.login(), credentials.server(), credentials.pairingToken());
  }

  /**
   * Updates the row AND publishes {@link BrokerConnectionEvent} to the {@code broker-connection}
   * topic — the plan's own spec for this endpoint bundles both, not split across two calls, so a
   * caller (Go) reporting a transition can't accidentally update the row without the rest of the
   * platform ever hearing about it.
   */
  public void updateConnectionStatus(UUID brokerAccountId, String status, String detail) {
    BrokerConnectionEventType eventType = toEventType(status);
    repository.updateConnectionStatus(brokerAccountId, status);

    EventEnvelope envelope =
        EventEnvelope.newBuilder()
            .setEventId(UUID.randomUUID().toString())
            .setOccurredAt(Instant.now().toString())
            .setSchemaVersion("v1")
            .build();
    BrokerConnectionEvent.Builder eventBuilder =
        BrokerConnectionEvent.newBuilder()
            .setEnvelope(envelope)
            .setBrokerAccountId(brokerAccountId.toString())
            .setEventType(eventType);
    if (detail != null) {
      eventBuilder.setDetail(detail);
    }
    brokerConnectionEventProducer.send(brokerAccountId.toString(), eventBuilder.build());
  }

  private BrokerConnectionEventType toEventType(String status) {
    return switch (status) {
      case "CONNECTED" -> BrokerConnectionEventType.BROKER_CONNECTION_EVENT_TYPE_ESTABLISHED;
      case "DEGRADED" -> BrokerConnectionEventType.BROKER_CONNECTION_EVENT_TYPE_DEGRADED;
      case "DISCONNECTED" -> BrokerConnectionEventType.BROKER_CONNECTION_EVENT_TYPE_LOST;
      case "REAUTH_REQUIRED" ->
          BrokerConnectionEventType.BROKER_CONNECTION_EVENT_TYPE_REAUTH_REQUIRED;
      default -> throw new InvalidConnectionStatusException();
    };
  }

  public record DecryptedCredentials(
      String accessToken, String refreshToken, long ctidTraderAccountId, boolean isLive) {}

  public record DecryptedMtCredentials(String login, String server, String pairingToken) {}
}
