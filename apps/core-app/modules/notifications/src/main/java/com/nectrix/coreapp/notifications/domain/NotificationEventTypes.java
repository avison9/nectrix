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

  /**
   * TICKET-118 follow-up — a Follower nominated a prospect for their Master to invite (real
   * dispatch target: {@code ProspectNominationService}, {@code modules:trading}, via the new {@code
   * notifications.api.NotificationDispatchApi}).
   */
  public static final String PROSPECT_NOMINATION_RECEIVED = "prospect_nomination.received";

  /**
   * TICKET-120 — one type covering all 4 real triggers (generated/sent/confirmed-deducted/
   * confirmed-paid) rather than 4 separate types: same target (the Master), same real distinction a
   * recipient cares about ("something about my fee report changed"), the body text (not the event
   * type) is what actually varies per trigger — see {@code BillingNotificationConsumer}.
   */
  public static final String FEE_REPORT_STATUS_CHANGED = "fee_report.status_changed";

  /**
   * TICKET-122 — one type covering both outcomes (approved/rejected), same "one type, body text
   * carries the real distinction" reasoning {@link #FEE_REPORT_STATUS_CHANGED} already established
   * — see {@code TierChangeRequestService#approve}/{@code #reject}.
   */
  public static final String TIER_CHANGE_REQUEST_DECIDED = "tier_change_request.decided";

  private static final Map<String, Set<Channel>> DEFAULT_ENABLED_CHANNELS =
      Map.of(
          COPIED_TRADE_OPENED, EnumSet.of(Channel.IN_APP, Channel.PUSH),
          COPIED_TRADE_CLOSED, EnumSet.of(Channel.IN_APP, Channel.PUSH),
          COPIED_TRADE_FAILED, EnumSet.of(Channel.IN_APP, Channel.PUSH),
          BROKER_CONNECTION_DEGRADED, EnumSet.of(Channel.IN_APP, Channel.PUSH, Channel.EMAIL),
          BROKER_CONNECTION_LOST, EnumSet.of(Channel.IN_APP, Channel.PUSH, Channel.EMAIL),
          DRAWDOWN_THRESHOLD_BREACHED, EnumSet.of(Channel.IN_APP, Channel.PUSH, Channel.EMAIL),
          INVOICE_GENERATED, EnumSet.of(Channel.IN_APP, Channel.EMAIL),
          PROSPECT_NOMINATION_RECEIVED, EnumSet.of(Channel.IN_APP, Channel.EMAIL),
          FEE_REPORT_STATUS_CHANGED, EnumSet.of(Channel.IN_APP, Channel.EMAIL),
          TIER_CHANGE_REQUEST_DECIDED, EnumSet.of(Channel.IN_APP, Channel.EMAIL));

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
