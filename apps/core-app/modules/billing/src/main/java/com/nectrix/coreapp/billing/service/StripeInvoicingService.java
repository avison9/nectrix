package com.nectrix.coreapp.billing.service;

import com.nectrix.coreapp.billing.config.BillingProperties;
import com.nectrix.coreapp.billing.domain.CopyRelationshipBillingRef;
import com.nectrix.coreapp.billing.repository.InvoiceRepository;
import com.nectrix.coreapp.billing.repository.PerformanceFeeLedgerRepository;
import com.nectrix.coreapp.billing.repository.SettlementDataRepository;
import com.nectrix.events.consumer.EventProducer;
import com.nectrix.events.v1.BillingEvent;
import com.nectrix.events.v1.BillingEventType;
import com.nectrix.events.v1.EventEnvelope;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Invoice;
import com.stripe.model.InvoiceItem;
import com.stripe.param.InvoiceCreateParams;
import com.stripe.param.InvoiceItemCreateParams;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * TICKET-113 §11.5 Option A — Stripe invoicing for {@code STRIPE_INVOICE} relationships only. Uses
 * Stripe's Invoicing API (an {@code InvoiceItem} + {@code Invoice}, {@code
 * collection_method=charge_automatically}), not a raw {@code PaymentIntent} — Stripe resolves the
 * customer's own default payment method server-side, so this service never needs to look one up
 * itself. {@code finalizeInvoice()} only *schedules* Stripe's automatic charge attempt
 * asynchronously — the actual PAID/FAILED outcome (AC5) arrives later via {@code
 * StripeWebhookController}, not synchronously from this call.
 *
 * <p><b>Known, flagged out-of-scope</b>: actually transferring {@code net_to_master_amount} to the
 * Master (a Stripe Connect payout or equivalent) is not built here — this service only collects
 * {@code master_fee_amount} from the Follower; the ledger records the correct split, but execution
 * of the master-side payout is a separate concern, same "platform's role is calculation/reporting,
 * not execution of money movement to the master" boundary §11.5 Option B already draws explicitly
 * for its own steps 4–5.
 */
@Service
public class StripeInvoicingService {

  private static final Logger log = LoggerFactory.getLogger(StripeInvoicingService.class);

  private final SettlementDataRepository dataRepository;
  private final InvoiceRepository invoiceRepository;
  private final PerformanceFeeLedgerRepository ledgerRepository;
  private final BillingProperties billingProperties;
  private final EventProducer<BillingEvent> billingEventProducer;

  public StripeInvoicingService(
      SettlementDataRepository dataRepository,
      InvoiceRepository invoiceRepository,
      PerformanceFeeLedgerRepository ledgerRepository,
      BillingProperties billingProperties,
      EventProducer<BillingEvent> billingEventProducer) {
    this.dataRepository = dataRepository;
    this.invoiceRepository = invoiceRepository;
    this.ledgerRepository = ledgerRepository;
    this.billingProperties = billingProperties;
    this.billingEventProducer = billingEventProducer;
  }

  @PostConstruct
  void configureApiKey() {
    Stripe.apiKey = billingProperties.stripe().apiKey();
  }

  public void invoice(CopyRelationshipBillingRef rel, UUID ledgerId, BigDecimal masterFeeAmount) {
    Optional<String> customerId = dataRepository.findStripeCustomerId(rel.followerUserId());
    if (customerId.isEmpty()) {
      recordFailure(rel.followerUserId(), ledgerId, masterFeeAmount, null);
      log.warn(
          "billing: no stripe_customer_id on file for userId={}, copyRelationshipId={} -- invoice not attempted",
          rel.followerUserId(),
          rel.id());
      return;
    }

    long amountCents =
        masterFeeAmount
            .multiply(BigDecimal.valueOf(100))
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact();
    try {
      InvoiceItem.create(
          InvoiceItemCreateParams.builder()
              .setCustomer(customerId.get())
              .setAmount(amountCents)
              .setCurrency("usd")
              .setDescription("Nectrix performance fee")
              .build());

      Invoice invoice =
          Invoice.create(
              InvoiceCreateParams.builder()
                  .setCustomer(customerId.get())
                  .setCollectionMethod(InvoiceCreateParams.CollectionMethod.CHARGE_AUTOMATICALLY)
                  .setAutoAdvance(true)
                  .build());
      invoice = invoice.finalizeInvoice();

      invoiceRepository.insert(
          rel.followerUserId(), ledgerId, masterFeeAmount, "OPEN", invoice.getId());
      ledgerRepository.updateStatus(ledgerId, "INVOICED");
      publishEvent(
          rel.followerUserId(),
          invoice.getId(),
          masterFeeAmount,
          BillingEventType.BILLING_EVENT_TYPE_INVOICE_GENERATED);
    } catch (StripeException e) {
      log.error("billing: Stripe invoice creation failed for copyRelationshipId={}", rel.id(), e);
      recordFailure(rel.followerUserId(), ledgerId, masterFeeAmount, null);
    }
  }

  private void recordFailure(
      UUID userId, UUID ledgerId, BigDecimal amount, String paymentProviderRef) {
    invoiceRepository.insert(userId, ledgerId, amount, "FAILED", paymentProviderRef);
    publishEvent(
        userId, paymentProviderRef, amount, BillingEventType.BILLING_EVENT_TYPE_INVOICE_FAILED);
    // Ledger status deliberately stays PENDING -- the fee is still owed, just not yet
    // successfully invoiced; see this class's own Javadoc.
  }

  private void publishEvent(
      UUID userId, String invoiceRef, BigDecimal amount, BillingEventType eventType) {
    EventEnvelope envelope =
        EventEnvelope.newBuilder()
            .setEventId(UUID.randomUUID().toString())
            .setOccurredAt(Instant.now().toString())
            .setSchemaVersion("v1")
            .build();
    BillingEvent.Builder builder =
        BillingEvent.newBuilder()
            .setEnvelope(envelope)
            .setUserId(userId.toString())
            .setEventType(eventType)
            .setAmount(amount.doubleValue())
            .setCurrency("USD");
    if (invoiceRef != null) {
      builder.setInvoiceId(invoiceRef);
    }
    billingEventProducer.send(userId.toString(), builder.build());
  }
}
