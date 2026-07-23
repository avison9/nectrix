package com.nectrix.coreapp.bootstrap.archival;

import com.nectrix.coreapp.billing.api.PerformanceFeeLedgerArchivalApi;
import com.nectrix.coreapp.billing.api.PerformanceFeeLedgerArchiveExport;
import com.nectrix.coreapp.invitations.api.BrokerAccountArchivalApi;
import com.nectrix.coreapp.invitations.api.BrokerAccountArchivalApi.BrokerAccountExportView;
import com.nectrix.coreapp.social.api.MasterProfileLookupApi;
import com.nectrix.coreapp.social.api.MasterProfileSummaryView;
import com.nectrix.coreapp.trading.api.CopyRelationshipArchivalApi;
import com.nectrix.coreapp.trading.api.CopyRelationshipArchiveExport;
import com.nectrix.coreapp.trading.api.CopyRelationshipArchiveExport.CopyRelationshipRecord;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * TICKET-101 follow-up — the one place that can legally reach {@code invitations}, {@code trading},
 * AND {@code billing}'s own archival {@code ..api..} facades together (see this feature's own
 * design doc for the module-dependency-cycle reasoning: nothing below {@code trading} in the real
 * Gradle dependency chain can call it without creating one).
 *
 * <p>Read-everything-first, upload, only-then-delete: if the blob upload fails, nothing has been
 * touched yet — {@link #archiveAndDelete} never deletes a row it hasn't already durably archived.
 * Deletion, once it starts, runs in the only FK-safe order (children before parents): {@code
 * performance_fee_ledger} (billing) &rarr; {@code copied_trades}/{@code trade_signals}/{@code
 * copy_relationships} (trading, {@code management_agreements} auto-cascades from that last step)
 * &rarr; the {@code broker_accounts} row itself (invitations).
 */
@Service
public class BrokerAccountArchivalOrchestrator {

  private final BrokerAccountArchivalApi brokerAccountArchivalApi;
  private final CopyRelationshipArchivalApi copyRelationshipArchivalApi;
  private final PerformanceFeeLedgerArchivalApi performanceFeeLedgerArchivalApi;
  private final MasterProfileLookupApi masterProfileLookupApi;
  private final MasterPrimaryBrokerAccountOrchestrator masterPrimaryBrokerAccountOrchestrator;
  private final ArchivalBlobStorageClient blobStorageClient;
  private final ArchivalLogRepository archivalLogRepository;
  private final ObjectMapper objectMapper;

  public BrokerAccountArchivalOrchestrator(
      BrokerAccountArchivalApi brokerAccountArchivalApi,
      CopyRelationshipArchivalApi copyRelationshipArchivalApi,
      PerformanceFeeLedgerArchivalApi performanceFeeLedgerArchivalApi,
      MasterProfileLookupApi masterProfileLookupApi,
      MasterPrimaryBrokerAccountOrchestrator masterPrimaryBrokerAccountOrchestrator,
      ArchivalBlobStorageClient blobStorageClient,
      ArchivalLogRepository archivalLogRepository,
      ObjectMapper objectMapper) {
    this.brokerAccountArchivalApi = brokerAccountArchivalApi;
    this.copyRelationshipArchivalApi = copyRelationshipArchivalApi;
    this.performanceFeeLedgerArchivalApi = performanceFeeLedgerArchivalApi;
    this.masterProfileLookupApi = masterProfileLookupApi;
    this.masterPrimaryBrokerAccountOrchestrator = masterPrimaryBrokerAccountOrchestrator;
    this.blobStorageClient = blobStorageClient;
    this.archivalLogRepository = archivalLogRepository;
    this.objectMapper = objectMapper;
  }

  /**
   * Reused by both triggers (the on-demand endpoint and the scheduled sweep) — one method, one code
   * path, so neither can silently drift from the other's behavior.
   */
  public ArchivalResult archiveAndDelete(UUID brokerAccountId) {
    BrokerAccountExportView brokerAccount = brokerAccountArchivalApi.findForExport(brokerAccountId);
    // Checked here, up front — not left to hardDelete's own guard at the very end — because by
    // the time hardDelete runs, trading/billing's rows would already be gone with no way back.
    // A still-connected account is never a valid archival target for either trigger.
    if (!"DISCONNECTED".equals(brokerAccount.connectionStatus())) {
      throw new IllegalStateException(
          "Broker account " + brokerAccountId + " is not DISCONNECTED, refusing to archive");
    }
    // Bugfix — same "checked up front, not left to the very end" reasoning as the DISCONNECTED
    // guard above: master_profiles.primary_broker_account_id is a NOT NULL FK with no cascade, so
    // hardDelete's own final DELETE would otherwise fail on it AFTER copy_relationships/
    // copied_trades/trade_signals/performance_fee_ledger are already gone, leaving this account
    // stuck in a half-torn-down state. Auto-reassign to another eligible account of this same
    // Master's if one exists; refuse up front, before anything is touched, if not.
    Optional<MasterProfileSummaryView> primaryOwner =
        masterProfileLookupApi.findByPrimaryBrokerAccountId(brokerAccountId);
    if (primaryOwner.isPresent()
        && masterPrimaryBrokerAccountOrchestrator
            .autoReassignForArchival(primaryOwner.get(), brokerAccountId)
            .isEmpty()) {
      throw new MasterPrimaryBrokerAccountRequiredException(brokerAccountId);
    }
    CopyRelationshipArchiveExport copyExport =
        copyRelationshipArchivalApi.findForExport(brokerAccountId);
    List<UUID> relationshipIds =
        copyExport.copyRelationships().stream().map(CopyRelationshipRecord::id).toList();
    PerformanceFeeLedgerArchiveExport billingExport =
        performanceFeeLedgerArchivalApi.findForExport(relationshipIds);

    Map<String, Integer> rowCounts = rowCounts(copyExport, billingExport);
    byte[] gzippedJsonl = toGzippedJsonl(brokerAccount, copyExport, billingExport);
    String blobKey = "broker-accounts/" + brokerAccountId + "/" + Instant.now() + ".jsonl.gz";

    // Durability checkpoint — everything above is read-only; nothing below this line runs
    // unless the archive itself is safely persisted first.
    blobStorageClient.putObject(blobKey, gzippedJsonl);

    performanceFeeLedgerArchivalApi.deleteForRelationships(relationshipIds);
    copyRelationshipArchivalApi.deleteForBrokerAccount(brokerAccountId);
    brokerAccountArchivalApi.hardDelete(brokerAccountId);

    archivalLogRepository.insert(
        brokerAccountId, blobKey, objectMapper.writeValueAsString(rowCounts));
    return new ArchivalResult(blobKey, rowCounts);
  }

  private Map<String, Integer> rowCounts(
      CopyRelationshipArchiveExport copyExport, PerformanceFeeLedgerArchiveExport billingExport) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("broker_accounts", 1);
    counts.put("copy_relationships", copyExport.copyRelationships().size());
    counts.put("copied_trades", copyExport.copiedTrades().size());
    counts.put("trade_signals", copyExport.tradeSignals().size());
    counts.put("performance_fee_ledger", billingExport.feeLedgerRows().size());
    counts.put("management_agreements", billingExport.managementAgreements().size());
    return counts;
  }

  /** One JSON object per line, each tagged with its source table — a flat, greppable audit blob. */
  private byte[] toGzippedJsonl(
      BrokerAccountExportView brokerAccount,
      CopyRelationshipArchiveExport copyExport,
      PerformanceFeeLedgerArchiveExport billingExport) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(buffer);
        Writer writer = new OutputStreamWriter(gzip, StandardCharsets.UTF_8)) {
      writeLine(writer, "broker_accounts", brokerAccount);
      for (var row : copyExport.copyRelationships()) {
        writeLine(writer, "copy_relationships", row);
      }
      for (var row : copyExport.copiedTrades()) {
        writeLine(writer, "copied_trades", row);
      }
      for (var row : copyExport.tradeSignals()) {
        writeLine(writer, "trade_signals", row);
      }
      for (var row : billingExport.feeLedgerRows()) {
        writeLine(writer, "performance_fee_ledger", row);
      }
      for (var row : billingExport.managementAgreements()) {
        writeLine(writer, "management_agreements", row);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return buffer.toByteArray();
  }

  private void writeLine(Writer writer, String table, Object row) throws IOException {
    writer.write(objectMapper.writeValueAsString(new ArchivalLine(table, row)));
    writer.write('\n');
  }

  private record ArchivalLine(String table, Object row) {}

  public record ArchivalResult(String blobKey, Map<String, Integer> rowCounts) {}
}
