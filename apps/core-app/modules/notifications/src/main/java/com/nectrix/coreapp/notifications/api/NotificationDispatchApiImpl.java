package com.nectrix.coreapp.notifications.api;

import com.nectrix.coreapp.notifications.service.NotificationDispatchService;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class NotificationDispatchApiImpl implements NotificationDispatchApi {

  private final NotificationDispatchService dispatchService;

  public NotificationDispatchApiImpl(NotificationDispatchService dispatchService) {
    this.dispatchService = dispatchService;
  }

  @Override
  public void dispatch(UUID userId, String eventType, String title, String body) {
    dispatchService.dispatch(userId, eventType, title, body);
  }
}
