package com.nectrix.coreapp.billing.service;

import com.nectrix.coreapp.audit.repository.AuditLogRepository;
import com.nectrix.coreapp.billing.domain.BrokerFeeReport;
import com.nectrix.coreapp.billing.domain.BrokerFeeReportLine;
import com.nectrix.coreapp.billing.repository.BrokerFeeReportRepository;
import com.nectrix.coreapp.billing.repository.BrokerFeeReportRepository.BundleCandidate;
import com.nectrix.coreapp.billing.storage.FeeReportDocumentStorageClient;
import com.nectrix.events.consumer.EventProducer;
import com.nectrix.events.v1.BillingEvent;
import com.nectrix.events.v1.BillingEventType;
import com.nectrix.events.v1.EventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * TICKET-120 — {@code BrokerFeeReport} generation/send/confirm, Master-scoped exactly like {@code
 * FeeLedgerService}'s own ownership shape: {@code master_profile_id} is always resolved fresh from
 * the caller's own JWT-derived user id, and every by-id action re-checks the resolved report's own
 * {@code masterProfileId} against it, 404ing (never 403 — no leaking that a differently-owned id is
 * valid) on mismatch.
 */
@Service
public class BrokerFeeReportService {

  private final BrokerFeeReportRepository repository;
  private final FeeReportDocumentStorageClient documentStorageClient;
  private final AuditLogRepository auditLogRepository;
  private final EventProducer<BillingEvent> billingEventProducer;

  public BrokerFeeReportService(
      BrokerFeeReportRepository repository,
      FeeReportDocumentStorageClient documentStorageClient,
      AuditLogRepository auditLogRepository,
      EventProducer<BillingEvent> billingEventProducer) {
    this.repository = repository;
    this.documentStorageClient = documentStorageClient;
    this.auditLogRepository = auditLogRepository;
    this.billingEventProducer = billingEventProducer;
  }

  /**
   * AC3/AC4 — bundles exactly the {@code PENDING} ledger rows for this Master's {@code
   * BROKER_PARTNERSHIP} relationships with {@code brokerType} (see {@link
   * BrokerFeeReportRepository#findBundleCandidates}'s own Javadoc), renders and durably uploads the
   * report document BEFORE the {@code broker_fee_reports}/{@code broker_fee_report_lines} rows are
   * ever written — if the upload fails, nothing below has run yet.
   */
  public BrokerFeeReport generate(
      UUID callerUserId, String brokerType, Instant periodStart, Instant periodEnd) {
    UUID masterProfileId = requireMasterProfileId(callerUserId);
    List<BundleCandidate> candidates = repository.findBundleCandidates(masterProfileId, brokerType);
    if (candidates.isEmpty()) {
      throw new NoPendingFeesToReportException();
    }
    String reportKey =
        "fee-reports/" + masterProfileId + "/" + brokerType + "/" + Instant.now() + ".csv";
    documentStorageClient.putObject(
        reportKey, renderReportDocument(candidates, brokerType, periodStart, periodEnd));
    UUID reportId =
        repository
            .tryInsertReport(
                masterProfileId, brokerType, periodStart, periodEnd, reportKey, callerUserId)
            .orElseThrow(DuplicateFeeReportException::new);
    for (BundleCandidate candidate : candidates) {
      repository.insertLine(reportId, candidate);
    }
    publishEvent(callerUserId, reportId, BillingEventType.BILLING_EVENT_TYPE_FEE_REPORT_GENERATED);
    return reload(reportId);
  }

  public List<BrokerFeeReport> listForMaster(UUID callerUserId) {
    UUID masterProfileId = requireMasterProfileId(callerUserId);
    return repository.findAllForMaster(masterProfileId);
  }

  /**
   * {@code documentUrl} — same "never a public URL" principle {@code ManagementAgreementView}
   * follows.
   */
  public record BrokerFeeReportDetail(
      BrokerFeeReport report, List<BrokerFeeReportLine> lines, String documentUrl) {}

  public BrokerFeeReportDetail getForMaster(UUID callerUserId, UUID reportId) {
    BrokerFeeReport report = requireOwnedReport(callerUserId, reportId);
    return new BrokerFeeReportDetail(
        report,
        repository.findLinesForReport(reportId),
        documentStorageClient.presignedGetUrl(report.reportObjectKey()).toString());
  }

  public BrokerFeeReport send(UUID callerUserId, UUID reportId) {
    BrokerFeeReport report = requireOwnedReport(callerUserId, reportId);
    requireStatus(report, "DRAFT", "send");
    repository.transitionStatus(reportId, "SENT", "sent_at", "REPORTED_TO_BROKER");
    audit(callerUserId, "FEE_REPORT_SENT", reportId);
    publishEvent(callerUserId, reportId, BillingEventType.BILLING_EVENT_TYPE_FEE_REPORT_SENT);
    return reload(reportId);
  }

  public BrokerFeeReport confirmDeducted(UUID callerUserId, UUID reportId) {
    BrokerFeeReport report = requireOwnedReport(callerUserId, reportId);
    requireStatus(report, "SENT", "confirm-deducted");
    repository.transitionStatus(
        reportId,
        "BROKER_CONFIRMED_DEDUCTED",
        "confirmed_deducted_at",
        "BROKER_CONFIRMED_DEDUCTED");
    audit(callerUserId, "FEE_REPORT_CONFIRMED_DEDUCTED", reportId);
    publishEvent(
        callerUserId, reportId, BillingEventType.BILLING_EVENT_TYPE_FEE_REPORT_CONFIRMED_DEDUCTED);
    return reload(reportId);
  }

  public BrokerFeeReport confirmPaid(UUID callerUserId, UUID reportId) {
    BrokerFeeReport report = requireOwnedReport(callerUserId, reportId);
    requireStatus(report, "BROKER_CONFIRMED_DEDUCTED", "confirm-paid");
    repository.transitionStatus(
        reportId, "BROKER_CONFIRMED_PAID", "confirmed_paid_at", "BROKER_CONFIRMED_PAID");
    audit(callerUserId, "FEE_REPORT_CONFIRMED_PAID", reportId);
    publishEvent(
        callerUserId, reportId, BillingEventType.BILLING_EVENT_TYPE_FEE_REPORT_CONFIRMED_PAID);
    return reload(reportId);
  }

  private void audit(UUID actorUserId, String action, UUID reportId) {
    auditLogRepository.insert(
        actorUserId, "USER", action, "broker_fee_report", reportId.toString(), null);
  }

  /**
   * {@code userId} is the Master themselves — {@code BillingNotificationConsumer} notifies them
   * directly.
   */
  private void publishEvent(UUID userId, UUID reportId, BillingEventType eventType) {
    EventEnvelope envelope =
        EventEnvelope.newBuilder()
            .setEventId(UUID.randomUUID().toString())
            .setOccurredAt(Instant.now().toString())
            .setSchemaVersion("v1")
            .build();
    BillingEvent event =
        BillingEvent.newBuilder()
            .setEnvelope(envelope)
            .setUserId(userId.toString())
            .setEventType(eventType)
            .setFeeReportId(reportId.toString())
            .build();
    billingEventProducer.send(userId.toString(), event);
  }

  private BrokerFeeReport requireOwnedReport(UUID callerUserId, UUID reportId) {
    UUID masterProfileId = requireMasterProfileId(callerUserId);
    BrokerFeeReport report =
        repository.findById(reportId).orElseThrow(BrokerFeeReportNotFoundException::new);
    if (!report.masterProfileId().equals(masterProfileId)) {
      throw new BrokerFeeReportNotFoundException();
    }
    return report;
  }

  private void requireStatus(BrokerFeeReport report, String required, String action) {
    if (!required.equals(report.status())) {
      throw new InvalidFeeReportTransitionException(
          action + " requires status=" + required + ", but current status is " + report.status());
    }
  }

  private UUID requireMasterProfileId(UUID userId) {
    return repository
        .findMasterProfileIdForUser(userId)
        .orElseThrow(MasterProfileRequiredException::new);
  }

  private BrokerFeeReport reload(UUID id) {
    return repository.findById(id).orElseThrow(BrokerFeeReportNotFoundException::new);
  }

  private byte[] renderReportDocument(
      List<BundleCandidate> candidates, String brokerType, Instant periodStart, Instant periodEnd) {
    StringBuilder sb = new StringBuilder();
    sb.append("NECTRIX BROKER FEE REPORT\n");
    sb.append("Broker type: ").append(brokerType).append('\n');
    sb.append("Period: ").append(periodStart).append(" to ").append(periodEnd).append("\n\n");
    sb.append("follower_broker_account_login,fee_amount,currency\n");
    BigDecimal total = BigDecimal.ZERO;
    for (BundleCandidate candidate : candidates) {
      sb.append(candidate.followerBrokerAccountLogin())
          .append(',')
          .append(candidate.feeAmount().toPlainString())
          .append(',')
          .append(candidate.currency())
          .append('\n');
      total = total.add(candidate.feeAmount());
    }
    sb.append('\n').append(String.format(Locale.ROOT, "TOTAL,%s,", total.toPlainString()));
    return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }
}
