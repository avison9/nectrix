package com.nectrix.coreapp.notifications.service;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import com.nectrix.coreapp.notifications.config.NotificationsProperties;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * TICKET-115 — one push integration covers both Android (FCM) and iOS (Firebase relays to APNs
 * internally for tokens registered via Firebase) — see this ticket's own plan for why a separate
 * raw APNs client isn't built. {@code serviceAccountJson} empty in dev/test (same "no real
 * credentials needed to boot" pattern {@code BillingProperties.stripe} already established) —
 * {@link #send} is a no-op (returns {@code false}) rather than throwing when unconfigured.
 */
@Service
public class FirebasePushSender implements PushSender {

  private static final Logger log = LoggerFactory.getLogger(FirebasePushSender.class);
  private static final String APP_NAME = "nectrix-notifications";

  private final NotificationsProperties properties;
  private FirebaseApp app;

  public FirebasePushSender(NotificationsProperties properties) {
    this.properties = properties;
  }

  @PostConstruct
  void init() {
    String json = properties.firebase() != null ? properties.firebase().serviceAccountJson() : null;
    if (json == null || json.isBlank()) {
      log.info("notifications: no Firebase service account configured, push delivery is a no-op");
      return;
    }
    try {
      FirebaseOptions options =
          FirebaseOptions.builder()
              .setCredentials(
                  GoogleCredentials.fromStream(
                      new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))))
              .build();
      this.app =
          FirebaseApp.getApps().stream()
              .filter(a -> APP_NAME.equals(a.getName()))
              .findFirst()
              .orElseGet(() -> FirebaseApp.initializeApp(options, APP_NAME));
    } catch (IOException e) {
      log.error(
          "notifications: failed to initialize Firebase Admin SDK, push delivery is a no-op", e);
    }
  }

  @Override
  public boolean send(String deviceToken, String title, String body) {
    if (app == null) {
      return false;
    }
    Message message =
        Message.builder()
            .setToken(deviceToken)
            .setNotification(Notification.builder().setTitle(title).setBody(body).build())
            .build();
    try {
      FirebaseMessaging.getInstance(app).send(message);
      return true;
    } catch (FirebaseMessagingException e) {
      log.warn("notifications: push send failed", e);
      return false;
    }
  }
}
