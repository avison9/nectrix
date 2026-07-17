package com.nectrix.coreapp.notifications.service;

/** Thin interface so {@link NotificationDispatchService} never touches the AWS SES SDK directly. */
public interface EmailSender {

  /**
   * @return true if the send genuinely succeeded, false on any failure (never throws).
   */
  boolean send(String recipientEmail, String subject, String body);
}
