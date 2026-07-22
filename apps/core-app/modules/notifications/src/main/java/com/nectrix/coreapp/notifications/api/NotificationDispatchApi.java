package com.nectrix.coreapp.notifications.api;

import java.util.UUID;

/**
 * TICKET-118 follow-up — lets {@code trading}'s {@code ProspectNominationService} dispatch a real
 * notification to a Master when a Follower nominates a prospect, without importing {@code
 * notifications.service}/{@code notifications.repository} directly (enforced by
 * ModuleBoundaryArchTest). Thin facade over {@code NotificationDispatchService#dispatch}'s own
 * 4-arg overload — preference resolution, in-app/push/email fan-out, and the WS live-push all still
 * happen exactly as they do for every other real dispatch caller (the 4 Kafka consumers).
 */
public interface NotificationDispatchApi {

  void dispatch(UUID userId, String eventType, String title, String body);
}
