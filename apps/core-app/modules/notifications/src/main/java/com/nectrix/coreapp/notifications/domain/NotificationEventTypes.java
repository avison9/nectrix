package com.nectrix.coreapp.notifications.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * TICKET-115 — the event-type -> default-enabled-channel table from
 * docs/12-analytics-notifications-admin.md §12.2, verbatim. Used as the fallback when no {@code
 * notification_preferences} row exists for a given user/event/channel — the DB column's own {@code
 * DEFAULT TRUE} only governs an already-*inserted* row, not the "no row at all" case, so preference
 * resolution must fall through to this table rather than silently treating "no row" as disabled.
 *
 * <p>{@code event_type} strings match exactly what the 4 Kafka consumers publish into {@code
 * notification_preferences}/{@code notification_log} (both columns are unconstrained {@code TEXT},
 * 009-notifications.sql) — this class is the single source of truth for those literal strings.
 */
public final class NotificationEventTypes {

  public enum Channel {
    IN_APP,
    PUSH,
    EMAIL,
    SMS
  }

  public static final String COPIED_TRADE_OPENED = "copied_trade.opened";
  public static final String COPIED_TRADE_CLOSED = "copied_trade.closed";
  public static final String COPIED_TRADE_FAILED = "copied_trade.failed";
  public static final String BROKER_CONNECTION_DEGRADED = "broker_connection.degraded";
  public static final String BROKER_CONNECTION_LOST = "broker_connection.lost";
  public static final String DRAWDOWN_THRESHOLD_BREACHED = "drawdown.threshold_breached";
  public static final String INVOICE_GENERATED = "invoice.generated";

  private static final Map<String, Set<Channel>> DEFAULT_ENABLED_CHANNELS =
      Map.of(
          COPIED_TRADE_OPENED, EnumSet.of(Channel.IN_APP, Channel.PUSH),
          COPIED_TRADE_CLOSED, EnumSet.of(Channel.IN_APP, Channel.PUSH),
          COPIED_TRADE_FAILED, EnumSet.of(Channel.IN_APP, Channel.PUSH),
          BROKER_CONNECTION_DEGRADED, EnumSet.of(Channel.IN_APP, Channel.PUSH, Channel.EMAIL),
          BROKER_CONNECTION_LOST, EnumSet.of(Channel.IN_APP, Channel.PUSH, Channel.EMAIL),
          DRAWDOWN_THRESHOLD_BREACHED, EnumSet.of(Channel.IN_APP, Channel.PUSH, Channel.EMAIL),
          INVOICE_GENERATED, EnumSet.of(Channel.IN_APP, Channel.EMAIL));

  /**
   * Every channel a real, valid event type could ever plausibly be delivered on — used to build the
   * full set to resolve preferences over (not just the ones already ON by default; a user may have
   * explicitly opted in to a channel that defaults to off, e.g. EMAIL for {@code
   * copied_trade.opened}).
   */
  private static final Set<Channel> ALL_CHANNELS =
      EnumSet.of(Channel.IN_APP, Channel.PUSH, Channel.EMAIL);

  private NotificationEventTypes() {}

  public static boolean isValid(String eventType) {
    return DEFAULT_ENABLED_CHANNELS.containsKey(eventType);
  }

  public static Set<Channel> defaultEnabledChannels(String eventType) {
    Set<Channel> defaults = DEFAULT_ENABLED_CHANNELS.get(eventType);
    if (defaults == null) {
      throw new InvalidNotificationEventTypeException(eventType);
    }
    return defaults;
  }

  public static Set<Channel> allChannels(String eventType) {
    if (!isValid(eventType)) {
      throw new InvalidNotificationEventTypeException(eventType);
    }
    return ALL_CHANNELS;
  }
}
