package com.nectrix.coreapp.invitations.service;

import com.nectrix.coreapp.audit.repository.AuditLogRepository;
import com.nectrix.coreapp.crypto.api.EnvelopeEncryptionService;
import com.nectrix.coreapp.invitations.repository.BrokerAccountRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Nectrix-hosted MT5/MT4 terminal provisioning — the ONE place a real plaintext broker password
 * leaves this JVM, for the ONE caller (apps/mt-terminal-host) that genuinely needs it to auto-login
 * a Wine-hosted terminal on the user's behalf. Deliberately a separate class from {@link
 * BrokerAccountInternalService}, not a new method on it — that class's {@code fetchMtCredentials}
 * method has its own Javadoc explicitly claiming it "deliberately never returns password"; keeping
 * this as a distinct class means that claim stays literally true, and every call to this
 * meaningfully more sensitive capability is a single, greppable, separately-audited call site (see
 * {@link #fetchMtTerminalCredentials}'s own audit-log write).
 *
 * <p>Decrypts the exact same {@code credentials_ciphertext} blob {@link
 * BrokerAccountInternalService#fetchMtCredentials} does (same {@link
 * MtLinkingService.MtStoredCredentials} shape, same {@link EnvelopeEncryptionService} call) — no
 * new encryption/storage shape needed, this is purely a different, more permissive read of data
 * already being stored for TICKET-102's own linking flow.
 */
@Service
public class MtTerminalCredentialService {

  private final BrokerAccountRepository repository;
  private final EnvelopeEncryptionService envelopeEncryptionService;
  private final ObjectMapper objectMapper;
  private final AuditLogRepository auditLogRepository;

  public MtTerminalCredentialService(
      BrokerAccountRepository repository,
      EnvelopeEncryptionService envelopeEncryptionService,
      ObjectMapper objectMapper,
      AuditLogRepository auditLogRepository) {
    this.repository = repository;
    this.envelopeEncryptionService = envelopeEncryptionService;
    this.objectMapper = objectMapper;
    this.auditLogRepository = auditLogRepository;
  }

  /**
   * Every call writes one {@code audit_log} row BEFORE returning — this is the single most
   * security-critical line added by the terminal-hosting work: the only durable record that a real
   * broker password left this JVM, for which account, and when.
   */
  public DecryptedMtTerminalCredentials fetchMtTerminalCredentials(UUID brokerAccountId) {
    BrokerAccountRepository.CredentialsRow row =
        repository
            .findCredentialsById(brokerAccountId)
            .orElseThrow(BrokerAccountNotFoundException::new);
    String ciphertext = new String(row.credentialsCiphertext(), StandardCharsets.UTF_8);
    String json = envelopeEncryptionService.decryptField(ciphertext, row.credentialsKeyVersion());
    MtLinkingService.MtStoredCredentials credentials =
        objectMapper.readValue(json, MtLinkingService.MtStoredCredentials.class);

    auditLogRepository.insert(
        null,
        "SYSTEM",
        "MT_TERMINAL_CREDENTIALS_FETCHED",
        "broker_account",
        brokerAccountId.toString(),
        null);

    return new DecryptedMtTerminalCredentials(
        credentials.login(),
        credentials.password(),
        credentials.server(),
        credentials.pairingToken());
  }

  public record DecryptedMtTerminalCredentials(
      String login, String password, String server, String pairingToken) {}
}
