package com.nectrix.coreapp.notifications.service;

import com.nectrix.coreapp.notifications.domain.NotificationLogEntry;
import com.nectrix.coreapp.notifications.repository.NotificationLogRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** TICKET-115 — the {@code GET/POST /notifications*} inbox endpoints' service layer. */
@Service
public class NotificationInboxService {

  private final NotificationLogRepository repository;

  public NotificationInboxService(NotificationLogRepository repository) {
    this.repository = repository;
  }

  public List<NotificationLogEntry> list(UUID userId, boolean unreadOnly) {
    return repository.findInbox(userId, unreadOnly);
  }

  public void markRead(UUID id, UUID userId) {
    repository.findByIdForUser(id, userId).orElseThrow(NotificationNotFoundException::new);
    repository.markRead(id);
  }
}
