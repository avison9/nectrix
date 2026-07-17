package com.nectrix.coreapp.billing.api;

import com.nectrix.coreapp.billing.domain.SubscriptionPlans;
import com.nectrix.coreapp.billing.service.SubscriptionService;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CapabilityLimitsApiImpl implements CapabilityLimitsApi {

  private final SubscriptionService subscriptionService;

  public CapabilityLimitsApiImpl(SubscriptionService subscriptionService) {
    this.subscriptionService = subscriptionService;
  }

  @Override
  public int maxMasterSlots(UUID userId) {
    return subscriptionService
        .getMine(userId)
        .map(s -> SubscriptionPlans.resolve(s.planCode()).maxMasterSlots())
        .orElse(0);
  }

  @Override
  public int maxFollowerSlots(UUID userId) {
    return subscriptionService
        .getMine(userId)
        .map(s -> SubscriptionPlans.resolve(s.planCode()).maxFollowerSlots())
        .orElse(0);
  }
}
