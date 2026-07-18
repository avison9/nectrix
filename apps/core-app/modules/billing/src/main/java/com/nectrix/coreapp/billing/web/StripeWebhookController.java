package com.nectrix.coreapp.billing.web;

import com.nectrix.coreapp.billing.config.BillingProperties;
import com.nectrix.coreapp.billing.repository.InvoiceRepository;
import com.nectrix.coreapp.billing.repository.SubscriptionRepository;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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
 *
 * <p>TICKET-114 — also the only place a local {@code subscriptions} row is ever created ({@code
 * checkout.session.completed}) or transitioned ({@code customer.subscription.updated}/{@code
 * .deleted}) — {@code SubscriptionService.startCheckout} only returns a Checkout URL, it never
 * writes a row itself, since Stripe is the source of truth for whether the card was actually
 * collected. Dispatch is now keyed on {@code event.getType()} first, then deserialized to the
 * matching {@code StripeObject} subtype per branch — the single {@code instanceof Invoice} check
 * TICKET-113 wrote wasn't enough once a second, unrelated Stripe object shape entered the picture.
 */
@RestController
public class StripeWebhookController {

  private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

  private final BillingProperties billingProperties;
  private final InvoiceRepository invoiceRepository;
  private final SubscriptionRepository subscriptionRepository;

  public StripeWebhookController(
      BillingProperties billingProperties,
      InvoiceRepository invoiceRepository,
      SubscriptionRepository subscriptionRepository) {
    this.billingProperties = billingProperties;
    this.invoiceRepository = invoiceRepository;
    this.subscriptionRepository = subscriptionRepository;
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
    if (dataObject.isEmpty()) {
      // API-version mismatch we can't deserialize -- ack anyway so Stripe doesn't retry forever.
      return ResponseEntity.ok().build();
    }

    switch (event.getType()) {
      case "invoice.paid" -> {
        if (dataObject.get() instanceof Invoice invoice) {
          invoiceRepository.markPaid(invoice.getId());
        }
      }
      case "invoice.payment_failed" -> {
        if (dataObject.get() instanceof Invoice invoice) {
          invoiceRepository.markFailed(invoice.getId());
        }
      }
      case "checkout.session.completed" -> {
        if (dataObject.get() instanceof Session session) {
          handleCheckoutCompleted(session);
        }
      }
      case "customer.subscription.updated", "customer.subscription.deleted" -> {
        if (dataObject.get() instanceof Subscription subscription) {
          handleSubscriptionTransition(subscription);
        }
      }
      default -> {
        // Ignored -- Stripe sends many other event types this endpoint doesn't act on.
      }
    }
    return ResponseEntity.ok().build();
  }

  /**
   * {@code clientReferenceId} (set at Session-creation time in {@code
   * SubscriptionService.startCheckout}) is the only way this handler learns which local user the
   * completed session belongs to -- Checkout Sessions carry nothing else of ours. Non-subscription
   * sessions (mode != subscription) or ones with no attached Subscription yet are ignored, not
   * errored -- Stripe can legitimately send this event for other Checkout uses this app doesn't
   * have, and re-delivery on a session this handler already processed (idempotent by design, since
   * {@code stripe_subscription_id} is unique) is expected, not exceptional.
   */
  private void handleCheckoutCompleted(Session session) {
    String userIdRaw = session.getClientReferenceId();
    String stripeSubscriptionId = session.getSubscription();
    if (userIdRaw == null || stripeSubscriptionId == null) {
      return;
    }
    if (subscriptionRepository.findByStripeSubscriptionId(stripeSubscriptionId).isPresent()) {
      return;
    }
    try {
      Subscription stripeSubscription = Subscription.retrieve(stripeSubscriptionId);
      SubscriptionItem item = stripeSubscription.getItems().getData().get(0);
      subscriptionRepository.insert(
          UUID.fromString(userIdRaw),
          resolvePlanCode(stripeSubscription),
          stripeSubscription.getStatus().toUpperCase(),
          Instant.ofEpochSecond(item.getCurrentPeriodStart()),
          Instant.ofEpochSecond(item.getCurrentPeriodEnd()),
          session.getCustomer(),
          stripeSubscriptionId);
    } catch (StripeException e) {
      log.error(
          "billing: failed to retrieve Stripe subscription {} after checkout.session.completed",
          stripeSubscriptionId,
          e);
    }
  }

  private void handleSubscriptionTransition(Subscription stripeSubscription) {
    SubscriptionItem item = stripeSubscription.getItems().getData().get(0);
    subscriptionRepository.updateStatusAndPeriod(
        stripeSubscription.getId(),
        stripeSubscription.getStatus().toUpperCase(),
        Instant.ofEpochSecond(item.getCurrentPeriodStart()),
        Instant.ofEpochSecond(item.getCurrentPeriodEnd()));
  }

  /**
   * The Stripe Price id on the subscription's one line item is the only signal this handler has —
   * mapped back to our own plan code via {@code BillingProperties.subscriptions().prices()} (the
   * same table {@code SubscriptionService.startCheckout} used in the other direction).
   */
  private String resolvePlanCode(Subscription stripeSubscription) {
    String priceId = stripeSubscription.getItems().getData().get(0).getPrice().getId();
    var prices = billingProperties.subscriptions().prices();
    if (priceId.equals(prices.starter())) {
      return "STARTER";
    }
    if (priceId.equals(prices.individual())) {
      return "INDIVIDUAL";
    }
    if (priceId.equals(prices.pro())) {
      return "PRO";
    }
    log.warn(
        "billing: unrecognized Stripe price id {} on subscription {}",
        priceId,
        stripeSubscription.getId());
    return "UNKNOWN";
  }
}
