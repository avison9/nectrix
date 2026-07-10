package com.nectrix.coreapp.invitations.service;

import com.nectrix.coreapp.crypto.api.EncryptedField;
import com.nectrix.coreapp.crypto.api.EnvelopeEncryptionService;
import com.nectrix.coreapp.invitations.client.CTraderOAuthClient;
import com.nectrix.coreapp.invitations.config.InvitationsProperties;
import com.nectrix.coreapp.invitations.repository.BrokerAccountRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * TICKET-101 task #120 — refreshes cTrader OAuth tokens nearing expiry via a plain REST call to
 * cTrader's token endpoint (see {@link CTraderOAuthClient#refreshToken}), before they'd otherwise
 * expire mid-session and force apps/broker-adapters into a real REAUTH_REQUIRED. A refresh call
 * that itself fails (the refresh token has been revoked/expired) is the one case that legitimately
 * flips {@code connection_status = REAUTH_REQUIRED} here — publishing {@link
 * com.nectrix.events.v1.BrokerConnectionEvent} via the same path {@link
 * BrokerAccountInternalService#updateConnectionStatus} uses, so this job and apps/broker-adapters'
 * own health-check-driven reports share one code path/one Kafka contract instead of two.
 *
 * <p>Every account is processed independently — one account's refresh failure must never abort the
 * whole batch.
 *
 * <p>{@code @ConditionalOnProperty} (default false, see InvitationsProperties.TokenRefresh's
 * Javadoc): this bean — and therefore its {@code @Scheduled} method — doesn't even exist unless
 * explicitly enabled, so no {@code @SpringBootTest} anywhere in the app accidentally fires real
 * outbound calls to openapi.ctrader.com just by loading a context that happens to contain
 * broker_accounts rows.
 */
@Service
@ConditionalOnProperty(
    prefix = "nectrix.invitations.token-refresh",
    name = "enabled",
    havingValue = "true")
public class TokenRefreshJob {

  private static final Logger log = LoggerFactory.getLogger(TokenRefreshJob.class);
  private static final String BROKER_TYPE = "CTRADER";
  // PENDING accounts are included too — a real refresh token can already be nearing expiry
  // before apps/broker-adapters ever gets around to its first Connect().
  private static final List<String> REFRESHABLE_STATUSES =
      List.of("PENDING", "CONNECTED", "DEGRADED", "REAUTH_REQUIRED");

  private final BrokerAccountRepository repository;
  private final CTraderOAuthClient oauthClient;
  private final EnvelopeEncryptionService envelopeEncryptionService;
  private final BrokerAccountInternalService internalService;
  private final ObjectMapper objectMapper;
  private final InvitationsProperties.TokenRefresh config;

  public TokenRefreshJob(
      BrokerAccountRepository repository,
      CTraderOAuthClient oauthClient,
      EnvelopeEncryptionService envelopeEncryptionService,
      BrokerAccountInternalService internalService,
      ObjectMapper objectMapper,
      InvitationsProperties props) {
    this.repository = repository;
    this.oauthClient = oauthClient;
    this.envelopeEncryptionService = envelopeEncryptionService;
    this.internalService = internalService;
    this.objectMapper = objectMapper;
    this.config = props.tokenRefresh();
  }

  @Scheduled(fixedDelayString = "${nectrix.invitations.token-refresh.interval-seconds:3600}000")
  public void refreshExpiringTokens() {
    List<BrokerAccountRepository.AccountRef> candidates =
        repository.findByStatusAndBrokerType(REFRESHABLE_STATUSES, BROKER_TYPE);
    for (BrokerAccountRepository.AccountRef account : candidates) {
      try {
        refreshIfNeeded(account.id());
      } catch (Exception e) {
        // One account's unexpected failure (network blip, transient DB error) must not abort
        // the rest of the batch — it's simply retried on the next scheduled run.
        log.error("token-refresh: unexpected failure for broker account {}", account.id(), e);
      }
    }
  }

  private void refreshIfNeeded(java.util.UUID brokerAccountId) {
    BrokerAccountRepository.CredentialsRow row =
        repository.findCredentialsById(brokerAccountId).orElse(null);
    if (row == null) {
      return; // deleted between listing and processing — nothing to do
    }
    String ciphertext = new String(row.credentialsCiphertext(), StandardCharsets.UTF_8);
    BrokerLinkingService.StoredCredentials credentials =
        objectMapper.readValue(
            envelopeEncryptionService.decryptField(ciphertext, row.credentialsKeyVersion()),
            BrokerLinkingService.StoredCredentials.class);

    if (!nearingExpiry(credentials.expiresAt())) {
      return;
    }

    try {
      CTraderOAuthClient.TokenResponse refreshed =
          oauthClient.refreshToken(credentials.refreshToken());
      String newExpiresAt =
          Instant.now()
              .plusSeconds(refreshed.expiresIn() != null ? refreshed.expiresIn() : 3600)
              .toString();
      String newJson =
          objectMapper.writeValueAsString(
              new BrokerLinkingService.StoredCredentials(
                  refreshed.accessToken(), refreshed.refreshToken(), newExpiresAt));
      EncryptedField encrypted = envelopeEncryptionService.encryptField(newJson);
      repository.updateCredentials(
          brokerAccountId,
          encrypted.ciphertext().getBytes(StandardCharsets.UTF_8),
          encrypted.keyVersion());
      log.info("token-refresh: refreshed broker account {}", brokerAccountId);
    } catch (Exception e) {
      log.warn(
          "token-refresh: refresh call failed for broker account {}, flipping to REAUTH_REQUIRED",
          brokerAccountId,
          e);
      internalService.updateConnectionStatus(
          brokerAccountId, "REAUTH_REQUIRED", "token refresh failed: " + e.getMessage());
    }
  }

  private boolean nearingExpiry(String expiresAt) {
    if (expiresAt == null) {
      return true; // no recorded expiry (shouldn't happen for a real row) — refresh defensively
    }
    Instant threshold = Instant.now().plusSeconds(config.refreshBeforeExpirySeconds());
    return Instant.parse(expiresAt).isBefore(threshold);
  }
}
