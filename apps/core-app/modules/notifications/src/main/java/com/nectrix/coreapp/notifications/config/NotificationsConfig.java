package com.nectrix.coreapp.notifications.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link NotificationsProperties} — mirrors modules/billing's own BillingConfig. */
@Configuration
@EnableConfigurationProperties(NotificationsProperties.class)
public class NotificationsConfig {}
