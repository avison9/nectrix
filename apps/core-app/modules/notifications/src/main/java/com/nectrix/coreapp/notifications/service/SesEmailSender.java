package com.nectrix.coreapp.notifications.service;

import com.nectrix.coreapp.notifications.config.NotificationsProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;

/**
 * TICKET-115 — {@code nectrix.notifications.ses.region}/{@code senderEmail} empty in dev/test (same
 * "no real credentials needed to boot" pattern {@code BillingProperties.stripe} already
 * established) — {@link #send} is a no-op (returns {@code false}) rather than throwing when
 * unconfigured.
 */
@Service
public class SesEmailSender implements EmailSender {

  private static final Logger log = LoggerFactory.getLogger(SesEmailSender.class);

  private final NotificationsProperties properties;
  private SesClient client;

  public SesEmailSender(NotificationsProperties properties) {
    this.properties = properties;
  }

  @PostConstruct
  void init() {
    String region = properties.ses() != null ? properties.ses().region() : null;
    if (region == null || region.isBlank()) {
      log.info("notifications: no SES region configured, email delivery is a no-op");
      return;
    }
    try {
      this.client = SesClient.builder().region(Region.of(region)).build();
    } catch (SdkException e) {
      log.error("notifications: failed to initialize SES client, email delivery is a no-op", e);
    }
  }

  @Override
  public boolean send(String recipientEmail, String subject, String body) {
    String senderEmail = properties.ses() != null ? properties.ses().senderEmail() : null;
    if (client == null || senderEmail == null || senderEmail.isBlank()) {
      return false;
    }
    try {
      client.sendEmail(
          r ->
              r.source(senderEmail)
                  .destination(d -> d.toAddresses(recipientEmail))
                  .message(
                      m -> m.subject(c -> c.data(subject)).body(b -> b.text(c -> c.data(body)))));
      return true;
    } catch (SdkException e) {
      log.warn("notifications: email send failed", e);
      return false;
    }
  }
}
