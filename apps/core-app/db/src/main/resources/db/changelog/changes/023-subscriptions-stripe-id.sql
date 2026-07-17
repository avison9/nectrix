--liquibase formatted sql

-- TICKET-114: subscriptions has payment_provider_customer_id (the Stripe *Customer*) but nothing
-- for the Stripe *Subscription* itself -- needed to retrieve/cancel/update the right Stripe object,
-- and for StripeWebhookController to resolve which local row a customer.subscription.updated/
-- .deleted event is about. Unique because a Stripe subscription belongs to exactly one local row.
--changeset nectrix:023-subscriptions-stripe-subscription-id
ALTER TABLE subscriptions ADD COLUMN stripe_subscription_id TEXT;
CREATE UNIQUE INDEX idx_subscriptions_stripe_subscription_id ON subscriptions(stripe_subscription_id)
    WHERE stripe_subscription_id IS NOT NULL;
--rollback DROP INDEX IF EXISTS idx_subscriptions_stripe_subscription_id;
--rollback ALTER TABLE subscriptions DROP COLUMN stripe_subscription_id;
