package com.nectrix.coreapp.notifications.service;

/**
 * Thin interface so {@link NotificationDispatchService} never touches the Firebase SDK directly.
 */
public interface PushSender {

  /**
   * @return true if the send genuinely succeeded, false on any failure (never throws).
   */
  boolean send(String deviceToken, String title, String body);
}
