package com.nectrix.coreapp.notifications.web;

import com.nectrix.coreapp.notifications.domain.InvalidNotificationEventTypeException;
import com.nectrix.coreapp.notifications.service.DrawdownFloorViolationException;
import com.nectrix.coreapp.notifications.service.InvalidNotificationChannelException;
import com.nectrix.coreapp.notifications.service.NotificationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Scoped to the whole {@code notifications.web} package — covers every controller in it. */
@RestControllerAdvice(basePackageClasses = NotificationController.class)
public class NotificationExceptionHandler {

  @ExceptionHandler(NotificationNotFoundException.class)
  public ResponseEntity<ErrorBody> handleNotFound() {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ErrorBody("notification_not_found"));
  }

  @ExceptionHandler(InvalidNotificationEventTypeException.class)
  public ResponseEntity<ErrorBody> handleInvalidEventType(InvalidNotificationEventTypeException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorBody("invalid_event_type: " + e.eventType()));
  }

  @ExceptionHandler(InvalidNotificationChannelException.class)
  public ResponseEntity<ErrorBody> handleInvalidChannel(InvalidNotificationChannelException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorBody("invalid_channel: " + e.channel()));
  }

  /** AC3 — attempting to disable drawdown.threshold_breached below the minimum severity floor. */
  @ExceptionHandler(DrawdownFloorViolationException.class)
  public ResponseEntity<ErrorBody> handleDrawdownFloorViolation() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorBody("drawdown_notification_floor_violation"));
  }

  public record ErrorBody(String error) {}
}
