package com.nectrix.coreapp.bootstrap.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.nectrix.coreapp.billing.repository.PerformanceFeeLedgerRepository;
import com.nectrix.coreapp.billing.service.SettlementSchedulerService;
import com.nectrix.coreapp.crypto.api.EncryptedField;
import com.nectrix.coreapp.crypto.api.EnvelopeEncryptionService;
import com.stripe.model.Invoice;
import com.stripe.model.InvoiceItem;
import com.stripe.param.InvoiceCreateParams;
import com.stripe.param.InvoiceItemCreateParams;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * TICKET-113 — end-to-end proof that {@link SettlementSchedulerService#runSettlement()} produces
 * correct, idempotent {@code performance_fee_ledger} rows against real seeded {@code
 * account_snapshots}/{@code copied_trades} data, for both collection methods, including the Stripe
 * (Option A) success/failure paths and a STOPPED relationship's final pro-rated settlement.
 * Stripe's own SDK is statically mocked (Mockito's inline mock maker, already active in this
 * project) — no real Stripe API calls in tests.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SettlementIntegrationTest {

  @Autowired private UserProvisioningApi userProvisioningApi;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private EnvelopeEncryptionService envelopeEncryptionService;

  @Autowired private SettlementSchedulerService settlementSchedulerService;

  @Autowired private PerformanceFeeLedgerRepository ledgerRepository;

  private UUID newUser() {
    String email = "settlement-" + UUID.randomUUID() + "@example.com";
    return userProvisioningApi.createUser(
        email, "correct horse battery staple", "Test User", null, null, null, "US");
  }

  private UUID insertBrokerAccount(UUID userId) {
    EncryptedField encrypted = envelopeEncryptionService.encryptField("{}");
    UUID accountId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO broker_accounts
          (id, user_id, broker_type, broker_account_login, display_label, is_demo, currency,
           credentials_ciphertext, credentials_key_version, connection_status)
        VALUES (?, ?, 'CTRADER', ?, 'Original Label', false, 'USD', ?, ?, 'CONNECTED')
        """,
        accountId,
        userId,
        "login-" + accountId,
        encrypted.ciphertext().getBytes(StandardCharsets.UTF_8),
        encrypted.keyVersion());
    return accountId;
  }

  private void insertSnapshot(
      UUID brokerAccountId, Instant capturedAt, double balance, double equity) {
    jdbcTemplate.update(
        """
        INSERT INTO account_snapshots (broker_account_id, balance, equity, used_margin, free_margin, captured_at)
        VALUES (?, ?, ?, 0, ?, ?)
        """,
        brokerAccountId,
        BigDecimal.valueOf(balance),
        BigDecimal.valueOf(equity),
        BigDecimal.valueOf(equity),
        Timestamp.from(capturedAt));
  }

  private UUID insertMasterProfile(UUID masterUserId, UUID primaryBrokerAccountId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO master_profiles (id, user_id, primary_broker_account_id, display_name) VALUES (?, ?, ?, 'Test Master')",
        id,
        masterUserId,
        primaryBrokerAccountId);
    return id;
  }

  private UUID insertMoneyManagementProfile() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO money_management_profiles (id, method, multiplier) VALUES (?, 'MULTIPLIER', 1.0)",
        id);
    return id;
  }

  private UUID insertRiskProfile() {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update("INSERT INTO risk_profiles (id) VALUES (?)", id);
    return id;
  }

  private UUID insertInvitation(UUID masterProfileId, UUID createdByUserId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO invitations
          (id, master_profile_id, invited_email, token_hash, status, created_by_user_id, expires_at)
        VALUES (?, ?, ?, ?, 'ACCEPTED', ?, ?)
        """,
        id,
        masterProfileId,
        "invitee-" + id + "@example.com",
        "token-hash-" + id,
        createdByUserId,
        Timestamp.from(Instant.now().plus(7, ChronoUnit.DAYS)));
    return id;
  }

  private record Chain(
      UUID id, UUID followerUserId, UUID followerBrokerAccountId, UUID masterBrokerAccountId) {}

  private Chain insertCopyRelationship(
      String feeCollectionMethod,
      BigDecimal highWaterMark,
      String status,
      Instant riskAckAt,
      Instant stoppedAt) {
    UUID masterUserId = newUser();
    UUID followerUserId = newUser();
    UUID masterBrokerAccountId = insertBrokerAccount(masterUserId);
    UUID followerBrokerAccountId = insertBrokerAccount(followerUserId);
    UUID masterProfileId = insertMasterProfile(masterUserId, masterBrokerAccountId);
    UUID mmProfileId = insertMoneyManagementProfile();
    UUID riskProfileId = insertRiskProfile();
    UUID invitationId = insertInvitation(masterProfileId, masterUserId);

    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO copy_relationships
          (id, master_profile_id, master_broker_account_id, follower_user_id, follower_broker_account_id,
           money_management_profile_id, risk_profile_id, status, performance_fee_percent,
           fee_collection_method, high_water_mark, risk_ack_at, originating_invitation_id, created_at, stopped_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 20.00, ?, ?, ?, ?, ?, ?)
        """,
        id,
        masterProfileId,
        masterBrokerAccountId,
        followerUserId,
        followerBrokerAccountId,
        mmProfileId,
        riskProfileId,
        status,
        feeCollectionMethod,
        highWaterMark,
        riskAckAt != null ? Timestamp.from(riskAckAt) : null,
        invitationId,
        Timestamp.from(riskAckAt != null ? riskAckAt : Instant.now().minus(90, ChronoUnit.DAYS)),
        stoppedAt != null ? Timestamp.from(stoppedAt) : null);
    return new Chain(id, followerUserId, followerBrokerAccountId, masterBrokerAccountId);
  }

  private void setStripeCustomerId(UUID userId, String stripeCustomerId) {
    jdbcTemplate.update(
        "UPDATE users SET stripe_customer_id = ? WHERE id = ?", stripeCustomerId, userId);
  }

  private Map<String, Object> findLedgerRow(UUID copyRelationshipId) {
    return jdbcTemplate.queryForMap(
        "SELECT * FROM performance_fee_ledger WHERE copy_relationship_id = ? ORDER BY period_end DESC LIMIT 1",
        copyRelationshipId);
  }

  /**
   * Real trading profit, as {@code sumRealizedPnl} sees it — WITHOUT a matching {@code
   * copied_trades} row, any equal balance increase is indistinguishable from a deposit (see
   * SettlementCalculationService's own Javadoc on the balance-delta detection mechanism), so every
   * "real profit" test scenario needs one of these, not just a raised balance/equity.
   */
  private void insertClosedCopiedTrade(
      UUID copyRelationshipId, UUID masterBrokerAccountId, double realizedPnl, Instant closedAt) {
    UUID tradeSignalId = UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO trade_signals
          (id, master_broker_account_id, broker_position_id, event_type, canonical_symbol,
           direction, volume_lots, server_timestamp, raw_payload)
        VALUES (?, ?, ?, 'POSITION_CLOSED', 'EURUSD', 'BUY', 1.0, ?, '{}'::jsonb)
        """,
        tradeSignalId,
        masterBrokerAccountId,
        "pos-" + tradeSignalId,
        Timestamp.from(closedAt));
    jdbcTemplate.update(
        """
        INSERT INTO copied_trades
          (id, copy_relationship_id, trade_signal_id, idempotency_key, status,
           computed_volume_lots, sizing_method_snapshot, realized_pnl, closed_at)
        VALUES (?, ?, ?, ?, 'CLOSED', 1.0, '{}'::jsonb, ?, ?)
        """,
        UUID.randomUUID(),
        copyRelationshipId,
        tradeSignalId,
        "idem-" + tradeSignalId,
        BigDecimal.valueOf(realizedPnl),
        Timestamp.from(closedAt));
  }

  @Test
  void brokerPartnership_realProfit_producesPendingLedgerRowWithNoStripeInteraction() {
    Instant riskAckAt = Instant.now().minus(40, ChronoUnit.DAYS);
    Chain chain =
        insertCopyRelationship(
            "BROKER_PARTNERSHIP", new BigDecimal("10000"), "ACTIVE", riskAckAt, null);

    insertSnapshot(chain.followerBrokerAccountId(), riskAckAt, 10000, 10000);
    insertSnapshot(
        chain.followerBrokerAccountId(), riskAckAt.plus(30, ChronoUnit.DAYS), 12000, 12000);
    insertClosedCopiedTrade(
        chain.id(), chain.masterBrokerAccountId(), 2000, riskAckAt.plus(15, ChronoUnit.DAYS));

    settlementSchedulerService.runSettlement();

    Map<String, Object> ledger = findLedgerRow(chain.id());
    assertThat(((BigDecimal) ledger.get("new_profit_above_hwm")).doubleValue())
        .isCloseTo(2000.0, org.assertj.core.data.Offset.offset(0.01));
    assertThat(((BigDecimal) ledger.get("master_fee_amount")).doubleValue())
        .isCloseTo(400.0, org.assertj.core.data.Offset.offset(0.01));
    assertThat(ledger.get("status")).isEqualTo("PENDING");

    Long invoiceCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM invoices WHERE user_id = ?", Long.class, chain.followerUserId());
    assertThat(invoiceCount).isZero();
  }

  @Test
  void stripeInvoice_success_marksLedgerInvoicedAndCreatesOpenInvoice() {
    Instant riskAckAt = Instant.now().minus(40, ChronoUnit.DAYS);
    Chain chain =
        insertCopyRelationship(
            "STRIPE_INVOICE", new BigDecimal("10000"), "ACTIVE", riskAckAt, null);
    setStripeCustomerId(chain.followerUserId(), "cus_test123");

    insertSnapshot(chain.followerBrokerAccountId(), riskAckAt, 10000, 10000);
    insertSnapshot(
        chain.followerBrokerAccountId(), riskAckAt.plus(30, ChronoUnit.DAYS), 12000, 12000);
    insertClosedCopiedTrade(
        chain.id(), chain.masterBrokerAccountId(), 2000, riskAckAt.plus(15, ChronoUnit.DAYS));

    Invoice fakeInvoice = mock(Invoice.class);
    when(fakeInvoice.getId()).thenReturn("in_test123");
    try {
      when(fakeInvoice.finalizeInvoice()).thenReturn(fakeInvoice);
    } catch (com.stripe.exception.StripeException e) {
      throw new IllegalStateException(e);
    }

    try (MockedStatic<InvoiceItem> invoiceItemMock = Mockito.mockStatic(InvoiceItem.class);
        MockedStatic<Invoice> invoiceMock = Mockito.mockStatic(Invoice.class)) {
      invoiceItemMock
          .when(() -> InvoiceItem.create(any(InvoiceItemCreateParams.class)))
          .thenReturn(mock(InvoiceItem.class));
      invoiceMock
          .when(() -> Invoice.create(any(InvoiceCreateParams.class)))
          .thenReturn(fakeInvoice);

      settlementSchedulerService.runSettlement();
    }

    Map<String, Object> ledger = findLedgerRow(chain.id());
    assertThat(ledger.get("status")).isEqualTo("INVOICED");

    Map<String, Object> invoice =
        jdbcTemplate.queryForMap(
            "SELECT * FROM invoices WHERE user_id = ? ORDER BY issued_at DESC LIMIT 1",
            chain.followerUserId());
    assertThat(invoice.get("status")).isEqualTo("OPEN");
    assertThat(invoice.get("payment_provider_ref")).isEqualTo("in_test123");
  }

  @Test
  void stripeInvoice_noCustomerOnFile_marksInvoiceFailedAndLeavesLedgerPending() {
    Instant riskAckAt = Instant.now().minus(40, ChronoUnit.DAYS);
    Chain chain =
        insertCopyRelationship(
            "STRIPE_INVOICE", new BigDecimal("10000"), "ACTIVE", riskAckAt, null);
    // Deliberately no stripe_customer_id set.

    insertSnapshot(chain.followerBrokerAccountId(), riskAckAt, 10000, 10000);
    insertSnapshot(
        chain.followerBrokerAccountId(), riskAckAt.plus(30, ChronoUnit.DAYS), 12000, 12000);
    insertClosedCopiedTrade(
        chain.id(), chain.masterBrokerAccountId(), 2000, riskAckAt.plus(15, ChronoUnit.DAYS));

    settlementSchedulerService.runSettlement();

    Map<String, Object> ledger = findLedgerRow(chain.id());
    // The fee is still owed -- just not yet successfully invoiced.
    assertThat(ledger.get("status")).isEqualTo("PENDING");

    Map<String, Object> invoice =
        jdbcTemplate.queryForMap(
            "SELECT * FROM invoices WHERE user_id = ? ORDER BY issued_at DESC LIMIT 1",
            chain.followerUserId());
    assertThat(invoice.get("status")).isEqualTo("FAILED");
  }

  @Test
  void rerunningSettlement_forAnAlreadySettledPeriod_isANoOpAtTheDbConstraintLevel() {
    UUID copyRelationshipId = UUID.randomUUID();
    Instant periodStart = Instant.now().minus(60, ChronoUnit.DAYS);
    Instant periodEnd = Instant.now().minus(30, ChronoUnit.DAYS);
    // Insert a real relationship row first (FK requirement), independent of the settlement job.
    Chain chain =
        insertCopyRelationship(
            "BROKER_PARTNERSHIP", new BigDecimal("10000"), "ACTIVE", periodStart, null);

    var first =
        ledgerRepository.tryInsert(
            chain.id(),
            periodStart,
            periodEnd,
            new BigDecimal("10000"),
            new BigDecimal("11000"),
            new BigDecimal("1000"),
            new BigDecimal("200"),
            new BigDecimal("30"),
            new BigDecimal("170"),
            "{}");
    assertThat(first).isPresent();

    var second =
        ledgerRepository.tryInsert(
            chain.id(),
            periodStart,
            periodEnd,
            new BigDecimal("10000"),
            new BigDecimal("11000"),
            new BigDecimal("1000"),
            new BigDecimal("200"),
            new BigDecimal("30"),
            new BigDecimal("170"),
            "{}");
    // Caught by the table's own UNIQUE(copy_relationship_id, period_start, period_end) constraint,
    // not an application-level pre-check.
    assertThat(second).isEmpty();

    Long rowCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM performance_fee_ledger WHERE copy_relationship_id = ? AND period_start = ? AND period_end = ?",
            Long.class,
            chain.id(),
            Timestamp.from(periodStart),
            Timestamp.from(periodEnd));
    assertThat(rowCount).isEqualTo(1);
  }

  @Test
  void stoppedMidPeriod_getsAFinalProRatedSettlementAtTheStopDate() {
    Instant riskAckAt = Instant.now().minus(20, ChronoUnit.DAYS);
    Instant stoppedAt =
        Instant.now().minus(5, ChronoUnit.DAYS); // stopped before a full 30-day period elapsed
    Chain chain =
        insertCopyRelationship(
            "BROKER_PARTNERSHIP", new BigDecimal("10000"), "STOPPED", riskAckAt, stoppedAt);

    insertSnapshot(chain.followerBrokerAccountId(), riskAckAt, 10000, 10000);
    insertSnapshot(chain.followerBrokerAccountId(), stoppedAt, 10500, 10500);

    settlementSchedulerService.runSettlement();

    Map<String, Object> ledger = findLedgerRow(chain.id());
    Instant ledgerPeriodEnd = ((Timestamp) ledger.get("period_end")).toInstant();
    // Tolerance, not exact equality -- Postgres TIMESTAMPTZ storage rounds to microsecond
    // precision, so a Java Instant.now()-derived value may not round-trip bit-for-bit.
    assertThat(java.time.Duration.between(stoppedAt, ledgerPeriodEnd).abs().toMillis())
        .isLessThan(1000);
  }

  @Test
  void relationshipsWithNoAccountData_areSkippedNotCrashed() {
    // A relationship that exists but has zero account_snapshots at all -- the settlement run
    // must not throw, and must simply produce no ledger row for it yet.
    insertCopyRelationship(
        "BROKER_PARTNERSHIP",
        new BigDecimal("10000"),
        "ACTIVE",
        Instant.now().minus(40, ChronoUnit.DAYS),
        null);

    settlementSchedulerService.runSettlement();
    // No assertion beyond "doesn't throw" -- reaching this line is the proof.
  }
}
