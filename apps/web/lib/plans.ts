import type { SubscriptionPlanCode } from "@nectrix/api-client";

// TICKET-114 — mirrors core-app's SubscriptionPlans.java catalog by hand: stable marketing
// content (names/prices/feature copy), not runtime state, so no public endpoint fetches this.
// Real prices/tiers are a product placeholder (the mock's own pricingPlans/regPlanOptions are
// templated loops with no literal content) — kept in exact sync with the Java catalog's
// maxMasterSlots/maxFollowerSlots when either changes.
export interface Plan {
  code: SubscriptionPlanCode;
  name: string;
  price: string;
  per: string;
  desc: string;
  features: string[];
  featured?: boolean;
}

export const PLANS: Plan[] = [
  {
    code: "STARTER",
    name: "Starter",
    price: "$9",
    per: "/mo",
    desc: "Try Nectrix solo, on your own accounts.",
    features: ["1 main account", "2 slave accounts", "Basic analytics", "14-day trial"],
  },
  {
    code: "INDIVIDUAL",
    name: "Individual",
    price: "$19",
    per: "/mo",
    desc: "For active solo traders running several strategies.",
    features: [
      "3 main accounts",
      "10 slave accounts",
      "Basic analytics",
      "Priority execution queue",
      "14-day trial",
    ],
    featured: true,
  },
  {
    code: "PRO",
    name: "Pro",
    price: "$49",
    per: "/mo",
    desc: "Maximum capacity for power users.",
    features: [
      "10 main accounts",
      "Unlimited slave accounts",
      "Advanced analytics",
      "Priority support",
      "14-day trial",
    ],
  },
];
