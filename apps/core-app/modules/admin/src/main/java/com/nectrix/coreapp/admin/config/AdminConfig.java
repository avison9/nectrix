package com.nectrix.coreapp.admin.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link AdminProperties} (Boot's documented preference over {@code @Component
 * + @ConfigurationProperties}) — mirrors {@code invitations.config.InvitationsConfig} doing the
 * same for {@code InvitationsProperties}.
 */
@Configuration
@EnableConfigurationProperties(AdminProperties.class)
public class AdminConfig {}
