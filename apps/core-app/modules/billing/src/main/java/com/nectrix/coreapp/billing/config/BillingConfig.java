package com.nectrix.coreapp.billing.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link BillingProperties} — mirrors modules/invitations' InvitationsConfig doing the
 * same for InvitationsProperties. No {@code @EnableScheduling} here: it's already enabled twice
 * over (modules/invitations' InvitationsConfig, TICKET-101; bootstrap's CoreAppApplication,
 * TICKET-112) — Spring tolerates the duplicate but a third declaration would just be noise.
 */
@Configuration
@EnableConfigurationProperties(BillingProperties.class)
public class BillingConfig {}
