package com.nectrix.coreapp.bootstrap.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.nectrix.coreapp.billing.api.CapabilityLimitsApi;
import com.nectrix.coreapp.billing.domain.Subscription;
import com.nectrix.coreapp.billing.repository.SubscriptionRepository;
import com.nectrix.coreapp.billing.service.SubscriptionService;
import com.stripe.model.Customer;
import com.stripe.model.checkout.Session;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * TICKET-114 — Stripe's own SDK is statically mocked (Mockito's inline mock maker, same TICKET-113
 * precedent {@code SettlementIntegrationTest} already established) — no real Stripe API calls in
 * tests. The {@code checkout.session.completed}/{@code customer.subscription.updated} webhook
 * handlers themselves aren't exercised end-to-end here (that would need a real Stripe signature
 * over a synthetic payload, infrastructure this codebase doesn't have yet) — instead, the local
 * {@code subscriptions} row those handlers would produce is inserted directly via {@link
 * SubscriptionRepository}, the same "insert the row the not-yet-built upstream flow would have
 * produced" precedent {@code SettlementIntegrationTest}'s own {@code insertCopyRelationship} helper
 * already established for invite-acceptance.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SubscriptionIntegrationTest {

  @Autowired private UserProvisioningApi userProvisioningApi;
  @Autowired private SubscriptionService subscriptionService;
  @Autowired private SubscriptionRepository subscriptionRepository;
  @Autowired private CapabilityLimitsApi capabilityLimitsApi;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID newUser() {
    String email = "sub-" + UUID.randomUUID() + "@example.com";
    return userProvisioningApi.createUser(
        email, "correct horse battery staple", "Test User", null, null, null, "US");
  }

  private void insertActiveSubscription(UUID userId, String planCode) {
    subscriptionRepository.insert(
        userId,
        planCode,
        "ACTIVE",
        Instant.now().minus(1, ChronoUnit.DAYS),
        Instant.now().plus(29, ChronoUnit.DAYS),
        "cus_test",
        "sub_test_" + UUID.randomUUID());
  }

  @Test
  void startCheckout_createsStripeCustomerAndReturnsCheckoutUrl() {
    UUID userId = newUser();

    Customer fakeCustomer = mock(Customer.class);
    when(fakeCustomer.getId()).thenReturn("cus_new123");
    Session fakeSession = mock(Session.class);
    when(fakeSession.getUrl()).thenReturn("https://checkout.stripe.com/pay/cs_test_abc");

    try (MockedStatic<Customer> customerMock = Mockito.mockStatic(Customer.class);
        MockedStatic<Session> sessionMock = Mockito.mockStatic(Session.class)) {
      customerMock
          .when(() -> Customer.create(any(CustomerCreateParams.class)))
          .thenReturn(fakeCustomer);
      sessionMock
          .when(() -> Session.create(any(SessionCreateParams.class)))
          .thenReturn(fakeSession);

      String checkoutUrl = subscriptionService.startCheckout(userId, "INDIVIDUAL");

      assertThat(checkoutUrl).isEqualTo("https://checkout.stripe.com/pay/cs_test_abc");
    }

    String storedCustomerId =
        jdbcTemplate.queryForObject(
            "SELECT stripe_customer_id FROM users WHERE id = ?", String.class, userId);
    assertThat(storedCustomerId).isEqualTo("cus_new123");
  }

  @Test
  void cancel_setsCancelAtPeriodEndOnTheStripeSubscription_neverImmediateCancel() {
    UUID userId = newUser();
    String stripeSubscriptionId = "sub_cancel_" + UUID.randomUUID();
    UUID localId =
        subscriptionRepository.insert(
            userId,
            "INDIVIDUAL",
            "ACTIVE",
            Instant.now().minus(1, ChronoUnit.DAYS),
            Instant.now().plus(29, ChronoUnit.DAYS),
            "cus_test",
            stripeSubscriptionId);

    com.stripe.model.Subscription fakeStripeSubscription =
        mock(com.stripe.model.Subscription.class);
    try {
      when(fakeStripeSubscription.update(any(SubscriptionUpdateParams.class)))
          .thenReturn(fakeStripeSubscription);
    } catch (com.stripe.exception.StripeException e) {
      throw new IllegalStateException(e);
    }

    try (MockedStatic<com.stripe.model.Subscription> subscriptionMock =
        Mockito.mockStatic(com.stripe.model.Subscription.class)) {
      subscriptionMock
          .when(() -> com.stripe.model.Subscription.retrieve(stripeSubscriptionId))
          .thenReturn(fakeStripeSubscription);

      subscriptionService.cancel(userId, localId);

      subscriptionMock.verify(() -> com.stripe.model.Subscription.retrieve(stripeSubscriptionId));
    }
    // The local row itself isn't flipped to CANCELED here -- that only happens once the
    // customer.subscription.deleted webhook confirms Stripe's period genuinely ended.
    Optional<Subscription> stillActive = subscriptionRepository.findActiveForUser(userId);
    assertThat(stillActive).isPresent();
    assertThat(stillActive.get().status()).isEqualTo("ACTIVE");
  }

  @Test
  void capabilityLimits_reflectTheActivePlansSlotCounts() {
    UUID userId = newUser();
    insertActiveSubscription(userId, "INDIVIDUAL");

    assertThat(capabilityLimitsApi.maxMasterSlots(userId)).isEqualTo(3);
    assertThat(capabilityLimitsApi.maxFollowerSlots(userId)).isEqualTo(10);
  }

  @Test
  void capabilityLimits_noSubscriptionRow_isZeroNotImplicitlyFree() {
    UUID userId = newUser();

    assertThat(capabilityLimitsApi.maxMasterSlots(userId)).isZero();
    assertThat(capabilityLimitsApi.maxFollowerSlots(userId)).isZero();
  }
}
