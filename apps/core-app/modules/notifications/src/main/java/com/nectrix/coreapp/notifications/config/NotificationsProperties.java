package com.nectrix.coreapp.notifications.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TICKET-115 — empty-safe defaults in {@code application.yml} (empty in dev/test, same "no real
 * credentials needed to boot" pattern {@code BillingProperties.stripe} already established) — both
 * {@link com.nectrix.coreapp.notifications.service.FirebasePushSender} and {@link
 * com.nectrix.coreapp.notifications.service.SesEmailSender} treat a blank config value as "delivery
 * for this channel is a no-op," not a startup failure.
 */
@ConfigurationProperties(prefix = "nectrix.notifications")
public record NotificationsProperties(
    Firebase firebase, Ses ses, LocalEmailCatcher localEmailCatcher) {

  /** {@code serviceAccountJson} is the raw JSON contents of a Firebase service-account key file. */
  public record Firebase(String serviceAccountJson) {}

  public record Ses(String region, String senderEmail) {}

  /**
   * TICKET-118 — local/dev-only escape hatch: when set, {@link
   * com.nectrix.coreapp.notifications.service.SesEmailSender} POSTs here (the {@code
   * webhook-catcher} dev container's {@code /email} route — see its own {@code main.go} comment)
   * instead of calling real AWS SES, so an invitation email's real link is visible locally without
   * needing real cloud credentials. Empty/unset in every real deployment (prod uses AWS SES for
   * real, a future GCP deployment will need its own GCP-equivalent sender — see SesEmailSender's
   * own Javadoc).
   */
  public record LocalEmailCatcher(String url) {}
}
