package com.nectrix.coreapp.notifications.api;

/**
 * TICKET-118 — the first real operation on this module's cross-module-sanctioned surface (enforced
 * by ModuleBoundaryArchTest — other modules may depend on {@code notifications.api}, never {@code
 * notifications.service}/{@code notifications.repository} directly). {@link
 * com.nectrix.coreapp.notifications.service.NotificationDispatchService} requires an existing
 * {@code userId} end-to-end (it looks the recipient's email up from one) — unusable for an
 * invitation email, which by definition targets an address with no platform account yet. This
 * exposes the lower-level {@code EmailSender} bean directly instead, the one primitive in this
 * module that already takes a raw email address.
 */
public interface EmailApi {

  /**
   * @return true if the send genuinely succeeded, false on any failure (never throws) — mirrors
   *     {@code EmailSender#send}'s own contract exactly.
   */
  boolean sendRaw(String recipientEmail, String subject, String body);
}
