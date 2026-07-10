package com.nectrix.coreapp.invitations.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Registers {@link InvitationsProperties} (Boot's documented preference over {@code @Component
 * + @ConfigurationProperties}) — mirrors modules/auth's SecurityConfig doing the same for
 * AuthProperties. {@code @EnableScheduling} is this platform's first (TICKET-101's TokenRefreshJob)
 * — safe to enable app-wide from here since no other {@code @Scheduled} method exists yet to
 * conflict with.
 */
@Configuration
@EnableConfigurationProperties(InvitationsProperties.class)
@EnableScheduling
public class InvitationsConfig {}
