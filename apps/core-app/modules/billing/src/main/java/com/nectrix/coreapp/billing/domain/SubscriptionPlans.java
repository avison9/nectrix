package com.nectrix.coreapp.billing.domain;

import com.nectrix.coreapp.billing.service.InvalidPlanCodeException;
import java.util.Map;

/**
 * TICKET-114 §Plan catalog — placeholder tiers/prices (a product decision this ticket explicitly
 * doesn't finalize; the mock's own {@code pricingPlans}/{@code regPlanOptions} are templated loops
 * with no literal content). {@code maxMasterSlots}/{@code maxFollowerSlots} count {@code
 * broker_accounts} rows by {@code connection_role} ({@code MASTER_ONLY}/{@code FOLLOWER_ONLY}) —
 * the self-directed "Individual" mode's own capacity, never Master/Follower marketplace
 * participation (that stays invite-governed, unaffected by any of this). {@code Integer.MAX_VALUE}
 * stands in for "unlimited" (PRO's follower-slot count) — same convention as leaving a DB column
 * NULL for "unbounded" elsewhere in this codebase, just at the Java layer since this catalog is a
 * static map, not a table.
 */
public final class SubscriptionPlans {

  public record Plan(String code, int maxMasterSlots, int maxFollowerSlots) {}

  private static final Map<String, Plan> CATALOG =
      Map.of(
          "STARTER", new Plan("STARTER", 1, 2),
          "INDIVIDUAL", new Plan("INDIVIDUAL", 3, 10),
          "PRO", new Plan("PRO", 10, Integer.MAX_VALUE));

  private SubscriptionPlans() {}

  public static Plan resolve(String planCode) {
    Plan plan = CATALOG.get(planCode);
    if (plan == null) {
      throw new InvalidPlanCodeException(planCode);
    }
    return plan;
  }

  public static boolean isValid(String planCode) {
    return CATALOG.containsKey(planCode);
  }
}
