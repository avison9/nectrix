package com.nectrix.coreapp.billing.web;

import com.nectrix.coreapp.billing.config.BillingProperties;
import com.nectrix.coreapp.billing.repository.InvoiceRepository;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * TICKET-113 — receives Stripe's async {@code invoice.paid}/{@code invoice.payment_failed} events
 * (the actual PAID/FAILED outcome of an invoice {@code StripeInvoicingService} created and
 * finalized synchronously, which only *schedules* Stripe's own automatic charge attempt). {@code
 * permitAll()} in SecurityConfig — deliberately NOT under {@code /internal/**} (that filter chain
 * expects OUR OWN shared service token, which Stripe can never present); security here is Stripe's
 * own signature scheme, verified in-controller via {@link Webhook#constructEvent}, the same "this
 * route has its own in-controller verification, not Spring Security's" precedent the cTrader OAuth
 * callback already established.
 */
@RestController
public class StripeWebhookController {

  private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

  private final BillingProperties billingProperties;
  private final InvoiceRepository invoiceRepository;

  public StripeWebhookController(
      BillingProperties billingProperties, InvoiceRepository invoiceRepository) {
    this.billingProperties = billingProperties;
    this.invoiceRepository = invoiceRepository;
  }

  @PostMapping("/webhooks/stripe")
  public ResponseEntity<Void> handle(
      @RequestBody String payload, @RequestHeader("Stripe-Signature") String signatureHeader) {
    Event event;
    try {
      event =
          Webhook.constructEvent(
              payload, signatureHeader, billingProperties.stripe().webhookSigningSecret());
    } catch (SignatureVerificationException e) {
      log.warn("billing: Stripe webhook signature verification failed", e);
      return ResponseEntity.status(400).build();
    }

    Optional<StripeObject> dataObject = event.getDataObjectDeserializer().getObject();
    if (dataObject.isEmpty() || !(dataObject.get() instanceof Invoice invoice)) {
      // Not an invoice-shaped event this endpoint cares about, or an API-version mismatch we
      // can't deserialize -- ack anyway so Stripe doesn't retry forever; nothing actionable here.
      return ResponseEntity.ok().build();
    }

    switch (event.getType()) {
      case "invoice.paid" -> invoiceRepository.markPaid(invoice.getId());
      case "invoice.payment_failed" -> invoiceRepository.markFailed(invoice.getId());
      default -> {
        // Ignored -- Stripe sends many other invoice.* event types this endpoint doesn't act on.
      }
    }
    return ResponseEntity.ok().build();
  }
}
