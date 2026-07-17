package com.nectrix.coreapp.bootstrap.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nectrix.coreapp.auth.api.UserProvisioningApi;
import com.nectrix.coreapp.notifications.repository.PushTokenRepository;
import com.nectrix.coreapp.notifications.service.EmailSender;
import com.nectrix.coreapp.notifications.service.NotificationDispatchService;
import com.nectrix.coreapp.notifications.service.NotificationPreferenceService;
import com.nectrix.coreapp.notifications.service.PushSender;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * TICKET-115 — {@link NotificationDispatchService}'s fan-out core, real DB + real {@link
 * NotificationPreferenceService} resolution, {@link PushSender}/{@link EmailSender} replaced with
 * {@code @MockitoBean}s (the real Firebase/SES-backed implementations are unconfigured in dev/test,
 * same "empty credentials, no-op sender" posture Stripe's own tests don't rely on either — this is
 * the direct equivalent of that same discipline, just via Spring bean override instead of static
 * mocking, since these SDKs are ordinary instantiated clients, not static-method-heavy like
 * Stripe).
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NotificationDispatchServiceIntegrationTest {

  @Autowired private UserProvisioningApi userProvisioningApi;
  @Autowired private NotificationDispatchService dispatchService;
  @Autowired private NotificationPreferenceService preferenceService;
  @Autowired private PushTokenRepository pushTokenRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean private PushSender pushSender;
  @MockitoBean private EmailSender emailSender;

  private UUID newUser() {
    String email = "dispatch-" + UUID.randomUUID() + "@example.com";
    return userProvisioningApi.createUser(
        email, "correct horse battery staple", "Test User", null, null, null, "US");
  }

  private java.util.List<Map<String, Object>> logRowsFor(UUID userId, String eventType) {
    return jdbcTemplate.queryForList(
        "SELECT * FROM notification_log WHERE user_id = ? AND event_type = ?", userId, eventType);
  }

  @Test
  void dispatch_defaultChannels_writesInAppAndPushRowsButNotEmail() {
    UUID userId = newUser();
    when(pushSender.send(any(), any(), any())).thenReturn(true);

    dispatchService.dispatch(userId, "copied_trade.opened", "Trade copied", "body");

    var rows = logRowsFor(userId, "copied_trade.opened");
    // copied_trade.opened defaults: IN_APP + PUSH on, EMAIL off (§12.2) -- no push token
    // registered, so PUSH is attempted-but-FAILED, not absent.
    assertThat(rows).extracting(r -> r.get("channel")).containsExactlyInAnyOrder("IN_APP", "PUSH");
    var inApp =
        rows.stream().filter(r -> "IN_APP".equals(r.get("channel"))).findFirst().orElseThrow();
    assertThat(inApp.get("status")).isEqualTo("SENT");
  }

  @Test
  void dispatch_pushWithRegisteredToken_callsSenderAndMarksSent() {
    UUID userId = newUser();
    pushTokenRepository.register(userId, "test-device-token", "ANDROID");
    when(pushSender.send(any(), any(), any())).thenReturn(true);

    dispatchService.dispatch(userId, "copied_trade.opened", "Trade copied", "body");

    verify(pushSender).send("test-device-token", "Trade copied", "body");
    var rows = logRowsFor(userId, "copied_trade.opened");
    var push = rows.stream().filter(r -> "PUSH".equals(r.get("channel"))).findFirst().orElseThrow();
    assertThat(push.get("status")).isEqualTo("SENT");
  }

  @Test
  void dispatch_userDisabledPushChannel_noPushRowAndSenderNeverCalled() {
    UUID userId = newUser();
    pushTokenRepository.register(userId, "test-device-token", "ANDROID");
    preferenceService.update(userId, "copied_trade.opened", "PUSH", false);

    dispatchService.dispatch(userId, "copied_trade.opened", "Trade copied", "body");

    var rows = logRowsFor(userId, "copied_trade.opened");
    assertThat(rows).extracting(r -> r.get("channel")).containsExactly("IN_APP");
    verify(pushSender, org.mockito.Mockito.never()).send(any(), any(), any());
  }

  @Test
  void dispatch_userOptsIntoEmailWhichDefaultsOff_emailRowIsWritten() {
    UUID userId = newUser();
    preferenceService.update(userId, "copied_trade.opened", "EMAIL", true);
    when(emailSender.send(any(), any(), any())).thenReturn(true);

    dispatchService.dispatch(userId, "copied_trade.opened", "Trade copied", "body");

    var rows = logRowsFor(userId, "copied_trade.opened");
    assertThat(rows).extracting(r -> r.get("channel")).contains("EMAIL");
  }

  @Test
  void dispatch_drawdownWithForceOverride_alwaysIncludesInAppAndPushRegardlessOfPreference() {
    UUID userId = newUser();
    // PUSH is user-disabled for this event type -- but severity=FORCE_CLOSE must override it.
    preferenceService.update(userId, "drawdown.threshold_breached", "PUSH", false);
    when(pushSender.send(any(), any(), any())).thenReturn(true);

    dispatchService.dispatch(
        userId, "drawdown.threshold_breached", "Drawdown breach", "body", true);

    var rows = logRowsFor(userId, "drawdown.threshold_breached");
    assertThat(rows).extracting(r -> r.get("channel")).contains("IN_APP", "PUSH");
  }
}
