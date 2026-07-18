package com.nectrix.coreapp.billing.service;

import com.nectrix.coreapp.billing.config.BillingProperties;
import com.nectrix.coreapp.billing.domain.Subscription;
import com.nectrix.coreapp.billing.domain.SubscriptionPlans;
import com.nectrix.coreapp.billing.repository.SettlementDataRepository;
import com.nectrix.coreapp.billing.repository.SubscriptionRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.checkout.Session;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * TICKET-114 — every plan (including the entry tier) requires a card on file, so this always ends
 * at a Stripe-hosted Checkout page (mode {@code SUBSCRIPTION}) rather than creating a {@code
 * Subscription} object directly the way an already-has-a-card flow could. No local {@code
 * subscriptions} row is created here — only {@code StripeWebhookController}'s {@code
 * checkout.session.completed} handler does that, once Stripe confirms the card was actually
 * collected. That's also why there's no "FREE, skip Stripe" branch (unlike a typical freemium
 * flow): every tier here is a real trial-then-auto-bill Stripe Price (docs' §11.6 "delegate
 * lifecycle to the processor" posture, just applied to the trial transition too).
 */
@Service
public class SubscriptionService {

  private final SubscriptionRepository repository;
  private final SettlementDataRepository userDataRepository;
  private final BillingProperties properties;

  public SubscriptionService(
      SubscriptionRepository repository,
      SettlementDataRepository userDataRepository,
      BillingProperties properties) {
    this.repository = repository;
    this.userDataRepository = userDataRepository;
    this.properties = properties;
  }

  /** Returns the Stripe-hosted Checkout URL to redirect the browser to. */
  public String startCheckout(UUID userId, String planCode) {
    SubscriptionPlans.resolve(planCode);
    String priceId = properties.subscriptions().prices().forPlanCode(planCode);
    String customerId = ensureStripeCustomer(userId);
    try {
      Session session =
          Session.create(
              SessionCreateParams.builder()
                  .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                  .addLineItem(
                      SessionCreateParams.LineItem.builder()
                          .setPrice(priceId)
                          .setQuantity(1L)
                          .build())
                  .setCustomer(customerId)
                  // Checkout Sessions carry nothing else of ours -- this is how the webhook
                  // resolves which local user a completed session belongs to.
                  .setClientReferenceId(userId.toString())
                  .setSuccessUrl(properties.subscriptions().successUrl())
                  .setCancelUrl(properties.subscriptions().cancelUrl())
                  .build());
      return session.getUrl();
    } catch (StripeException e) {
      throw new CheckoutSessionFailedException(e);
    }
  }

  private String ensureStripeCustomer(UUID userId) {
    Optional<String> existing = userDataRepository.findStripeCustomerId(userId);
    if (existing.isPresent()) {
      return existing.get();
    }
    try {
      String email = userDataRepository.findEmail(userId);
      Customer customer = Customer.create(CustomerCreateParams.builder().setEmail(email).build());
      userDataRepository.updateStripeCustomerId(userId, customer.getId());
      return customer.getId();
    } catch (StripeException e) {
      throw new CheckoutSessionFailedException(e);
    }
  }

  /**
   * {@code setCancelAtPeriodEnd(true)} — not an immediate {@code .cancel()} -- Stripe keeps {@code
   * status=active} until the period genuinely ends, then fires {@code
   * customer.subscription.deleted}, which is what actually flips the local row to {@code CANCELED}
   * (see {@code StripeWebhookController}). This is what makes "downgrade at period end, not
   * immediately" true without any local expiry-scheduling of our own.
   */
  public void cancel(UUID userId, UUID subscriptionId) {
    Subscription existing =
        repository
            .findActiveForUser(userId)
            .filter(s -> s.id().equals(subscriptionId))
            .orElseThrow(SubscriptionNotFoundException::new);
    try {
      com.stripe.model.Subscription stripeSubscription =
          com.stripe.model.Subscription.retrieve(existing.stripeSubscriptionId());
      stripeSubscription.update(
          SubscriptionUpdateParams.builder().setCancelAtPeriodEnd(true).build());
    } catch (StripeException e) {
      throw new CheckoutSessionFailedException(e);
    }
  }

  public Optional<Subscription> getMine(UUID userId) {
    return repository.findActiveForUser(userId);
  }
}
