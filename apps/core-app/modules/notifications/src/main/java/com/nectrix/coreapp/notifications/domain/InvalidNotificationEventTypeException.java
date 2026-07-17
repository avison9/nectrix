package com.nectrix.coreapp.notifications.domain;

/** Mapped to a clean 400 by the preferences controller — never a bare NPE/KeyError. */
public class InvalidNotificationEventTypeException extends RuntimeException {

  private final String eventType;

  public InvalidNotificationEventTypeException(String eventType) {
    this.eventType = eventType;
  }

  public String eventType() {
    return eventType;
  }
}
