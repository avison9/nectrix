package com.nectrix.coreapp.notifications.service;

/** Mapped to a clean 400 — {@code channel} must be one of PUSH/EMAIL/SMS/IN_APP. */
public class InvalidNotificationChannelException extends RuntimeException {

  private final String channel;

  public InvalidNotificationChannelException(String channel) {
    this.channel = channel;
  }

  public String channel() {
    return channel;
  }
}
