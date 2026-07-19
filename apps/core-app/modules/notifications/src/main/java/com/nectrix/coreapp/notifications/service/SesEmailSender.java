package com.nectrix.coreapp.notifications.service;

import com.nectrix.coreapp.notifications.config.NotificationsProperties;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;

/**
 * TICKET-115 — {@code nectrix.notifications.ses.region}/{@code senderEmail} empty in dev/test (same
 * "no real credentials needed to boot" pattern {@code BillingProperties.stripe} already
 * established) — {@link #send} is a no-op (returns {@code false}) rather than throwing when
 * unconfigured.
 *
 * <p>TICKET-118 — {@code nectrix.notifications.local-email-catcher.url}, if set, takes priority
 * over real SES: every send instead POSTs to that URL (the local-dev {@code webhook-catcher}
 * container's {@code /email} route, {@code docker-compose.yml}), so an invitation email's real link
 * is visible locally without any real cloud credentials. Never set in any real deployment.
 *
 * <p><b>Production/GCP note</b>: real delivery today is AWS SES-only (this class). A production
 * deployment needs real {@code nectrix.notifications.ses.region}/{@code senderEmail} plus an IAM
 * role/credentials with {@code ses:SendEmail}. If/when this platform also deploys to GCP, GCP has
 * no SES equivalent client library — that deployment will need its own sender (e.g. via Gmail
 * API/SendGrid/Mailgun, or a small SMTP relay), selected the same way {@code
 * LocalEmailCatcher}/real-SES already branch here — this class does not attempt to guess which
 * cloud it's running in.
 */
@Service
public class SesEmailSender implements EmailSender {

  private static final Logger log = LoggerFactory.getLogger(SesEmailSender.class);

  private final NotificationsProperties properties;
  private final RestClient restClient = RestClient.create();
  private SesClient client;

  public SesEmailSender(NotificationsProperties properties) {
    this.properties = properties;
  }

  @PostConstruct
  void init() {
    if (localCatcherUrl() != null) {
      log.info("notifications: local email catcher configured, real SES is bypassed");
      return;
    }
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
    String catcherUrl = localCatcherUrl();
    if (catcherUrl != null) {
      return sendToLocalCatcher(catcherUrl, recipientEmail, subject, body);
    }
    return sendViaSes(recipientEmail, subject, body);
  }

  private String localCatcherUrl() {
    NotificationsProperties.LocalEmailCatcher catcher = properties.localEmailCatcher();
    String url = catcher != null ? catcher.url() : null;
    return url != null && !url.isBlank() ? url : null;
  }

  private boolean sendToLocalCatcher(
      String catcherUrl, String recipientEmail, String subject, String body) {
    try {
      restClient
          .post()
          .uri(catcherUrl + "/email")
          .body(Map.of("recipient_email", recipientEmail, "subject", subject, "body", body))
          .retrieve()
          .toBodilessEntity();
      return true;
    } catch (Exception e) {
      log.warn("notifications: local email catcher POST failed", e);
      return false;
    }
  }

  private boolean sendViaSes(String recipientEmail, String subject, String body) {
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
