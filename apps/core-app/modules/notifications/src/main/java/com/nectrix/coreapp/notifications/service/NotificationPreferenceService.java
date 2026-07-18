package com.nectrix.coreapp.notifications.service;

import com.nectrix.coreapp.notifications.domain.InvalidNotificationEventTypeException;
import com.nectrix.coreapp.notifications.domain.NotificationEventTypes;
import com.nectrix.coreapp.notifications.domain.NotificationEventTypes.Channel;
import com.nectrix.coreapp.notifications.domain.NotificationPreference;
import com.nectrix.coreapp.notifications.repository.NotificationPreferenceRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * TICKET-115 — preference resolution (with catalog-default fallback for rows that don't exist yet)
 * and the drawdown minimum-severity floor rule (docs/12-analytics-notifications-admin.md §12.2's
 * own note: {@code drawdown.threshold_breached} "cannot be fully disabled below a minimum severity"
 * — enforced here as "{@code IN_APP} can never be turned off for this event type," the one channel
 * with no delivery-provider dependency).
 */
@Service
public class NotificationPreferenceService {

  private final NotificationPreferenceRepository repository;

  public NotificationPreferenceService(NotificationPreferenceRepository repository) {
    this.repository = repository;
  }

  public List<NotificationPreference> findAllForUser(UUID userId) {
    return repository.findAllForUser(userId);
  }

  /**
   * The channels this event should actually deliver on for this user — an explicit {@code
   * notification_preferences} row wins, otherwise falls back to {@link
   * NotificationEventTypes#defaultEnabledChannels}.
   */
  public Set<Channel> resolveEnabledChannels(UUID userId, String eventType) {
    if (!NotificationEventTypes.isValid(eventType)) {
      throw new InvalidNotificationEventTypeException(eventType);
    }
    Map<String, Boolean> explicit =
        repository.findForUserAndEventType(userId, eventType).stream()
            .collect(
                Collectors.toMap(NotificationPreference::channel, NotificationPreference::enabled));
    Set<Channel> defaults = NotificationEventTypes.defaultEnabledChannels(eventType);

    Set<Channel> resolved = EnumSet.noneOf(Channel.class);
    for (Channel channel : NotificationEventTypes.allChannels(eventType)) {
      Boolean explicitValue = explicit.get(channel.name());
      boolean enabled = explicitValue != null ? explicitValue : defaults.contains(channel);
      if (enabled) {
        resolved.add(channel);
      }
    }
    return resolved;
  }

  public void update(UUID userId, String eventType, String channelRaw, boolean enabled) {
    if (!NotificationEventTypes.isValid(eventType)) {
      throw new InvalidNotificationEventTypeException(eventType);
    }
    Channel channel = parseChannel(channelRaw);
    if (NotificationEventTypes.DRAWDOWN_THRESHOLD_BREACHED.equals(eventType)
        && channel == Channel.IN_APP
        && !enabled) {
      throw new DrawdownFloorViolationException();
    }
    repository.upsert(userId, eventType, channel.name(), enabled);
  }

  private Channel parseChannel(String channelRaw) {
    try {
      return Channel.valueOf(channelRaw);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new InvalidNotificationChannelException(channelRaw);
    }
  }
}
